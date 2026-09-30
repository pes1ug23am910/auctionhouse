#!/usr/bin/env python3
"""Release immutable app/gateway pairs through exact owned SSM documents and ALB targets."""
import argparse
import json
import re
import subprocess
import time
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlsplit


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--release', type=Path, required=True)
    parser.add_argument('--profile', help='Optional named operator profile; omit for short-lived CI environment credentials')
    parser.add_argument('--mode', choices=['deploy', 'migrate', 'warehouse'], default='deploy')
    parser.add_argument('--cut-id', default='00000000-0000-0000-0000-000000000000')
    parser.add_argument('--schema-compatible', action='store_true', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text())
    release = json.loads(args.release.read_text())
    if set(release) != {'appDigest', 'gatewayDigest', 'warehouseDigest'} or not all(re.fullmatch(r'sha256:[a-f0-9]{64}', value) for value in release.values()):
        raise SystemExit('Release must contain exactly three immutable SHA256 digests')
    if args.output.exists():
        raise SystemExit('Evidence output must be a new directory')
    if not re.fullmatch(r'[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}', args.cut_id):
        raise SystemExit('Invalid immutable cut identifier')
    owner = manifest['ownership']
    expiry = datetime.fromisoformat(owner['expiresAt'].replace('Z', '+00:00'))
    origin = urlsplit(manifest['origin'])
    if owner.get('project') != 'auctionhouse' or expiry.utcoffset() is None or expiry <= datetime.now(timezone.utc):
        raise SystemExit('Current full deployment ownership and future expiry are required')
    if origin.scheme != 'https' or not origin.hostname or origin.username or origin.password or origin.port not in [None, 443] or origin.path or origin.query or origin.fragment:
        raise SystemExit('Manifest must name one canonical HTTPS origin')
    apps = manifest['applicationInstanceIds']
    dependency = manifest['dependencyInstanceId']
    if manifest['schemaVersion'] != 1 or manifest['applicationHostCount'] not in [1, 2] or len(apps) != manifest['applicationHostCount'] or len(set(apps + [dependency])) != len(apps) + 1:
        raise SystemExit('Invalid full topology manifest')
    args.output.mkdir(parents=True)
    prefix = ['aws', '--region', owner['region'], '--output', 'json', '--no-cli-pager'] + (['--profile', args.profile] if args.profile else [])

    def aws(*parts):
        process = subprocess.run(prefix + list(parts), capture_output=True, text=True, timeout=120)
        if process.returncode:
            raise RuntimeError('AWS command failed: ' + ' '.join(parts[:2]))
        return json.loads(process.stdout or '{}')

    def owned(tags):
        values = {tag['Key']: tag['Value'] for tag in tags}
        return values.get('Project') == 'auctionhouse' and values.get('SessionId') == owner['sessionId'] and values.get('Topology') == 'full' and values.get('ExpiresAt') == owner['expiresAt']

    if aws('sts', 'get-caller-identity')['Account'] != owner['accountId']:
        raise SystemExit('AWS identity mismatch')
    instances = aws('ec2', 'describe-instances', '--instance-ids', *apps, dependency)
    actual = {item['InstanceId']: item for reservation in instances['Reservations'] for item in reservation['Instances']}
    if set(actual) != set(apps + [dependency]) or not all(owned(item.get('Tags', [])) and item['State']['Name'] == 'running' for item in actual.values()):
        raise SystemExit('Exact running owned instance inventory required')
    for instance, expected_role in [(value, 'app') for value in apps] + [(dependency, 'dependency')]:
        if dict((tag['Key'], tag['Value']) for tag in actual[instance]['Tags']).get('ServiceRole') != expected_role:
            raise SystemExit('Instance role does not match the full topology manifest')
    targets = [manifest['loadBalancer']['targetGroupArn'], manifest['loadBalancer']['gatewayTargetGroupArn']]
    for arn in targets + [manifest['loadBalancer']['arn']]:
        if not owned(aws('elbv2', 'describe-tags', '--resource-arns', arn)['TagDescriptions'][0]['Tags']):
            raise SystemExit('ALB/target group ownership mismatch')
    expected_document = f"auctionhouse-{owner['sessionId']}-full-release"
    expected_inspect = f"auctionhouse-{owner['sessionId']}-full-inspect"
    if manifest['releaseDocumentName'] != expected_document or manifest['inspectionDocumentName'] != expected_inspect:
        raise SystemExit('Unexpected command document name')
    documents = {}
    expected_commands = {
        expected_document: '/opt/auctionhouse/full/release.sh "$SSM_Mode" "$SSM_AppDigest" "$SSM_GatewayDigest" "$SSM_WarehouseDigest" "$SSM_CutId" "$SSM_Compatibility"',
        expected_inspect: 'python3 /opt/auctionhouse/full/inspect-runtime.py',
    }
    for name, expected_command in expected_commands.items():
        document = aws('ssm', 'get-document', '--name', name, '--document-format', 'JSON')
        content = json.loads(document['Content'])
        steps = content.get('mainSteps', [])
        if not document['DocumentVersion'].isdigit() or len(steps) != 1 or steps[0].get('action') != 'aws:runShellScript' or steps[0]['inputs'].get('runCommand') != [expected_command]:
            raise SystemExit('Installed SSM document differs from the fixed reviewed command')
        if name == expected_inspect and content.get('parameters'):
            raise SystemExit('Inspection document must not accept parameters')
        documents[name] = document['DocumentVersion']

    def command(instance, mode, desired):
        parameters = {'Mode': [mode], 'AppDigest': [desired['appDigest']], 'GatewayDigest': [desired['gatewayDigest']],
                      'WarehouseDigest': [desired['warehouseDigest']], 'CutId': [args.cut_id], 'Compatibility': ['schema-compatible']}
        command_id = aws('ssm', 'send-command', '--document-name', expected_document, '--document-version', documents[expected_document], '--instance-ids', instance,
                         '--parameters', json.dumps(parameters))['Command']['CommandId']
        for attempt in range(190):
            try:
                result = aws('ssm', 'get-command-invocation', '--command-id', command_id, '--instance-id', instance)
            except RuntimeError:
                if attempt > 5:
                    raise
                time.sleep(2)
                continue
            if result['Status'] in ['Pending', 'InProgress', 'Delayed']:
                time.sleep(5)
                continue
            # Preserve only identity/status: command output may contain operational details.
            (args.output / f'{instance}-{mode}-{command_id}.json').write_text(json.dumps({
                'commandId': command_id, 'instanceId': instance, 'mode': mode, 'status': result['Status'],
                'responseCode': result.get('ResponseCode'), 'release': desired}, indent=2) + '\n')
            if result['Status'] != 'Success':
                raise RuntimeError('Scoped release command failed; inspect private SSM output')
            return
        raise RuntimeError('Scoped release command did not finish within its bounded window')

    def inspect(instance):
        command_id = aws('ssm', 'send-command', '--document-name', expected_inspect, '--document-version', documents[expected_inspect], '--instance-ids', instance)['Command']['CommandId']
        for attempt in range(30):
            try:
                result = aws('ssm', 'get-command-invocation', '--command-id', command_id, '--instance-id', instance)
                if result['Status'] == 'Success':
                    return json.loads(result['StandardOutputContent'])
                if result['Status'] not in ['Pending', 'InProgress', 'Delayed']:
                    raise ValueError('Inspection failed')
            except RuntimeError:
                pass
            time.sleep(2)
        raise RuntimeError('Inspection unavailable')

    def registration(instance, enabled):
        operation = 'register-targets' if enabled else 'deregister-targets'
        for group in targets:
            aws('elbv2', operation, '--target-group-arn', group, '--targets', f'Id={instance}')
        if not enabled:
            time.sleep(32)  # Matches configured 30-second SSE drain, with a bounded margin.

    def healthy(instance):
        for _ in range(45):
            states = [aws('elbv2', 'describe-target-health', '--target-group-arn', group,
                          '--targets', f'Id={instance}')['TargetHealthDescriptions'][0]['TargetHealth']['State'] for group in targets]
            if all(state == 'healthy' for state in states):
                return
            time.sleep(4)
        raise RuntimeError('Target did not become healthy')

    if args.mode == 'migrate':
        command(apps[0], 'migrate', release)
        return
    if args.mode == 'warehouse':
        command(dependency, 'warehouse', release)
        return
    before = {instance: inspect(instance) for instance in apps}
    (args.output / 'before.json').write_text(json.dumps(before, indent=2) + '\n')
    command(dependency, 'dependencies', release)
    command(apps[0], 'migrate', release)
    updated = []
    current = None
    try:
        for current in apps:
            registration(current, False)
            command(current, 'deploy', release)
            registration(current, True)
            healthy(current)
            updated.append(current)
        readiness_url = manifest['origin'] + '/actuator/health/readiness'
        with urllib.request.urlopen(readiness_url, timeout=10) as response:
            if response.geturl() != readiness_url or response.status != 200 or json.load(response).get('status') != 'UP':
                raise RuntimeError('Public TLS smoke failed')
        after = {instance: inspect(instance) for instance in apps}
        for inspection in after.values():
            if inspection['release'] != release or not inspection['readiness']:
                raise RuntimeError('Release inspection mismatch')
            containers = {row['name']: row for row in inspection['containers']}
            for kind in ['app', 'gateway']:
                row = containers.get('auctionhouse-' + kind, {})
                expected = manifest['ecrRepositoryUrls'][kind] + '@' + release[kind + 'Digest']
                if not row.get('running') or row.get('imageReference') != expected or not row.get('readOnlyRootFilesystem') or not row.get('demoCredentialsAbsent'):
                    raise RuntimeError('Actual container artifact/security settings differ from the release manifest')
        (args.output / 'after.json').write_text(json.dumps(after, indent=2) + '\n')
        print('Full immutable release verified; capture real login, negatives, trace and cut evidence separately')
    except Exception:
        # A partial rollout must not leave a mixed successful/failed image population.
        restore = list(dict.fromkeys(updated + ([current] if current else [])))
        restore_errors = []
        for instance in reversed(restore):
            try:
                prior = before[instance].get('release')
                registration(instance, False)
                if prior:
                    command(instance, 'rollback', prior)
                    registration(instance, True)
                    healthy(instance)
                else:
                    command(instance, 'deactivate', release)
            except Exception:
                restore_errors.append(instance)
        (args.output / 'rollback-status.json').write_text(json.dumps({'attempted': restore, 'failed': restore_errors}, indent=2) + '\n')
        raise RuntimeError('Release failed; inspect rollback-status.json before any further deployment')


if __name__ == '__main__':
    main()
