#!/usr/bin/env python3
"""One-time operator bootstrap through an already-open SSM tunnel. Never run in CI."""
import argparse
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile


def aws(*args, input_data=None):
    # Capture output: a successful secret read must never reach stdout/logs.
    result = subprocess.run(['aws', '--profile', options.profile, '--region', options.region, *args],
                            input=input_data, text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError('AWS bootstrap operation failed; review permissions without logging secret responses')
    return json.loads(result.stdout or '{}')


def parameter(name, value):
    # Reuse a previously staged secret after a failed DB bootstrap; never rotate silently.
    result = subprocess.run(['aws', '--profile', options.profile, '--region', options.region, 'ssm',
                             'get-parameter', '--with-decryption', '--name', name], capture_output=True, text=True)
    if result.returncode == 0:
        return json.loads(result.stdout)['Parameter']['Value']
    if 'ParameterNotFound' not in result.stderr:
        raise RuntimeError('Cannot inspect bootstrap parameter')
    payload = {'Name': name, 'Type': 'SecureString', 'Tier': 'Standard', 'Value': value,
               'Tags': [{'Key': k, 'Value': v} for k,v in {
                   'Project':'auctionhouse', 'SessionId':options.session, 'ExpiresAt':options.expires_at,
                   'ManagedBy':'auctionhouse-bootstrap', **({'Topology':'full'} if options.namespace == 'full' else {})}.items()]}
    # Linux stdin avoids putting the secret in argv or a disk file.
    aws('ssm', 'put-parameter', '--cli-input-json', 'file:///dev/stdin', input_data=json.dumps(payload))
    return value


parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--profile', default='auctionhouse')
for name in ['region','account-id','session','expires-at','master-secret-arn','db-host','ca-file','issuer','client-id']:
    parser.add_argument('--'+name, required=True)
parser.add_argument('--tunnel-port', type=int, default=15432)
parser.add_argument('--namespace', choices=['thin', 'full'], default='thin', help='Keep full-stack SSM parameters separate from thin deployment credentials')
options=parser.parse_args()
if os.name != 'posix':
    raise SystemExit('Use a private Linux/WSL operator shell; stdin secret transfer requires /dev/stdin')
if not options.issuer.startswith('https://'):
    raise SystemExit('Cloud OIDC issuer must use HTTPS')
if aws('sts','get-caller-identity')['Account'] != options.account_id:
    raise SystemExit('AWS project identity mismatch')
try:
    master=json.loads(aws('secretsmanager','get-secret-value','--secret-id',options.master_secret_arn)['SecretString'])
    prefix='/auctionhouse/'+options.session+('/full' if options.namespace == 'full' else '')
    migration=parameter(prefix+'/migration', secrets.token_urlsafe(32))
    runtime=json.loads(parameter(prefix+'/runtime',json.dumps({
        'spring.datasource.password':secrets.token_urlsafe(32),
        'spring.security.oauth2.client.registration.keycloak.client-id':options.client_id,
        'spring.security.oauth2.client.provider.keycloak.issuer-uri':options.issuer})))
    runtime_password=runtime['spring.datasource.password']
    # Only generated URL-safe passwords can reach SQL literal construction.
    import re
    if not all(re.fullmatch(r'[A-Za-z0-9_-]{32,128}',p) for p in [migration,runtime_password]):
        raise RuntimeError('Bootstrap credentials have unexpected format')
    sql=f'''
BEGIN;
DO $$ BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='ah_migrator') THEN CREATE ROLE ah_migrator LOGIN; END IF;
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='ah_runtime') THEN CREATE ROLE ah_runtime LOGIN; END IF;
END $$;
ALTER ROLE ah_migrator PASSWORD '{migration}' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
ALTER ROLE ah_runtime PASSWORD '{runtime_password}' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
REVOKE ALL ON DATABASE auctionhouse FROM PUBLIC;
GRANT CONNECT ON DATABASE auctionhouse TO ah_migrator, ah_runtime;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT ah_migrator TO ah_bootstrap;
ALTER SCHEMA public OWNER TO ah_migrator;
GRANT USAGE ON SCHEMA public TO ah_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE ah_migrator IN SCHEMA public GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO ah_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE ah_migrator IN SCHEMA public GRANT USAGE,SELECT ON SEQUENCES TO ah_runtime;
COMMIT;
'''
    env=dict(os.environ,PGHOST=options.db_host,PGHOSTADDR='127.0.0.1',PGPORT=str(options.tunnel_port),
             PGDATABASE='auctionhouse',PGUSER=master['username'],PGPASSWORD=master['password'],
             PGSSLMODE='verify-full',PGSSLROOTCERT=str(Path(options.ca_file).resolve()))
    result=subprocess.run(['psql','-X','--quiet','--set','ON_ERROR_STOP=on'],input=sql,text=True,capture_output=True,env=env)
    if result.returncode:
        raise RuntimeError('Database bootstrap failed; staged SSM credentials remain available for retry')
    print('Bootstrap complete: separate schema-owner and DML roles; credentials stored only in SSM')
except Exception:
    # Deliberately do not print exception payloads, subprocess output, SQL or secrets.
    raise SystemExit('Bootstrap failed. Inspect connectivity/permissions; rerun safely with the same project/session inputs.')
