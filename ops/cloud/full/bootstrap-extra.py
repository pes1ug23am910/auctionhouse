#!/usr/bin/env python3
"""One-time full-stack warehouse/Grafana secret bootstrap after V1-V5 migrations."""
import argparse
import json
import os
import re
import secrets
import subprocess
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--profile', default='auctionhouse')
    for name in ['region', 'account-id', 'session', 'expires-at', 'master-secret-arn', 'db-host', 'ca-file']:
        parser.add_argument('--' + name, required=True)
    parser.add_argument('--master-secret-env', help='Read master JSON from this environment variable instead of Secrets Manager; consume it before starting child processes')
    parser.add_argument('--tunnel-port', type=int, default=15432)
    options = parser.parse_args()
    if os.name != 'posix':
        raise SystemExit('Use a private Linux/WSL operator shell with /dev/stdin')
    command = ['aws', '--profile', options.profile, '--region', options.region, '--output', 'json', '--no-cli-pager']

    def aws(*args, payload=None):
        result = subprocess.run(command + list(args), input=payload, capture_output=True, text=True)
        if result.returncode:
            raise RuntimeError('AWS operation failed')
        return json.loads(result.stdout or '{}')

    def parameter(kind):
        name = f'/auctionhouse/{options.session}/full/{kind}'
        result = subprocess.run(command + ['ssm', 'get-parameter', '--with-decryption', '--name', name], capture_output=True, text=True)
        if result.returncode == 0:
            return json.loads(result.stdout)['Parameter']['Value']
        if 'ParameterNotFound' not in result.stderr:
            raise RuntimeError('Cannot inspect staged parameter')
        value = secrets.token_urlsafe(32)
        body = {'Name': name, 'Type': 'SecureString', 'Tier': 'Standard', 'Value': value, 'Tags': [
            {'Key': key, 'Value': text} for key, text in {
                'Project': 'auctionhouse', 'SessionId': options.session, 'Topology': 'full',
                'ExpiresAt': options.expires_at, 'ManagedBy': 'auctionhouse-bootstrap'}.items()]}
        aws('ssm', 'put-parameter', '--cli-input-json', 'file:///dev/stdin', payload=json.dumps(body))
        return value

    try:
        # Consume external input before any child process can inherit the master JSON.
        supplied_master = os.environ.pop(options.master_secret_env) if options.master_secret_env is not None else None
        if aws('sts', 'get-caller-identity')['Account'] != options.account_id:
            raise RuntimeError('Account mismatch')
        master = json.loads(supplied_master if options.master_secret_env is not None else
                            aws('secretsmanager', 'get-secret-value', '--secret-id', options.master_secret_arn)['SecretString'])
        if not isinstance(master, dict) or not all(
            isinstance(master.get(key), str) and master[key] and '\x00' not in master[key]
            and '{{resolve:secretsmanager:' not in master[key] for key in ['username', 'password']
        ):
            raise ValueError('Invalid master credential input')
        password = parameter('warehouse')
        parameter('grafana')
        if not re.fullmatch(r'[A-Za-z0-9_-]{32,128}', password):
            raise RuntimeError('Unexpected staged credential format')
        statement = f"""BEGIN;
DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='ah_warehouse') THEN CREATE ROLE ah_warehouse LOGIN; END IF; END $$;
ALTER ROLE ah_warehouse PASSWORD '{password}' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
GRANT CONNECT ON DATABASE auctionhouse TO ah_warehouse;
GRANT USAGE ON SCHEMA public TO ah_warehouse;
GRANT SELECT ON event_cuts,event_cut_members,outbox_events,notification_effects,notification_deliveries TO ah_warehouse;
COMMIT;
"""
        environment = dict(os.environ, PGHOST=options.db_host, PGHOSTADDR='127.0.0.1', PGPORT=str(options.tunnel_port),
                           PGDATABASE='auctionhouse', PGUSER=master['username'], PGPASSWORD=master['password'],
                           PGSSLMODE='verify-full', PGSSLROOTCERT=str(Path(options.ca_file).resolve()))
        result = subprocess.run(['psql', '-X', '--quiet', '--set', 'ON_ERROR_STOP=on'], input=statement,
                                text=True, capture_output=True, env=environment)
        if result.returncode:
            raise RuntimeError('Warehouse role bootstrap failed; V1-V5 must exist')
        print('Full-stack bootstrap complete: read-only warehouse role and separate Grafana secret')
    except Exception:
        raise SystemExit('Full-stack bootstrap failed; inspect connectivity/permissions without printing secrets or SQL')


if __name__ == '__main__':
    main()
