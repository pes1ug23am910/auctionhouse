#!/usr/bin/env python3
"""Full-root backend/ownership preflight and exact saved-plan teardown. Never provisions resources."""
import argparse
import hashlib
import json
import os
import subprocess
from pathlib import Path


def validate_destroy(plan, session, accept_dependency_data_loss=False):
    changes = [change for change in plan.get('resource_changes', []) if change.get('mode') == 'managed']
    owned = {}
    for change in changes:
        before = change['change'].get('before') or {}
        tags = before.get('tags_all') or before.get('tags') or {}
        if tags.get('Project') == 'auctionhouse' and tags.get('SessionId') == session and tags.get('Topology') == 'full':
            owned[change['address']] = (change['type'], before)
    by_type = lambda kind: [before for resource_type, before in owned.values() if resource_type == kind]
    for change in changes:
        if any(action not in ['delete', 'no-op'] for action in change['change']['actions']):
            raise ValueError('Only delete/no-op changes are allowed in a teardown plan')
        before = change['change'].get('before') or {}
        kind = change['type']
        if kind == 'aws_ebs_volume' and 'delete' in change['change']['actions'] and not accept_dependency_data_loss:
            raise ValueError('Explicit acceptance of dependency-volume data loss is required; preserve an approved snapshot first if needed')
        if change['address'] in owned:
            continue
        allowed = False
        if kind in ['aws_iam_role_policy', 'aws_iam_role_policy_attachment']:
            allowed = any(before.get('role') == role.get('name') for role in by_type('aws_iam_role'))
            if kind == 'aws_iam_role_policy_attachment':
                allowed = allowed and before.get('policy_arn') == 'arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore'
        elif kind == 'aws_route_table_association':
            subnets = [row for row in by_type('aws_subnet') if row.get('id') == before.get('subnet_id')]
            routes = [row for row in by_type('aws_route_table') if row.get('id') == before.get('route_table_id')]
            allowed = len(subnets) == len(routes) == 1 and subnets[0].get('vpc_id') == routes[0].get('vpc_id')
        elif kind == 'aws_lb_target_group_attachment':
            allowed = any(row.get('arn') == before.get('target_group_arn') for row in by_type('aws_lb_target_group')) and any(
                row.get('id') == before.get('target_id') and (row.get('tags_all') or row.get('tags') or {}).get('ServiceRole') == 'app' for row in by_type('aws_instance'))
        elif kind == 'aws_volume_attachment':
            allowed = any(row.get('id') == before.get('volume_id') for row in by_type('aws_ebs_volume')) and any(
                row.get('id') == before.get('instance_id') and (row.get('tags_all') or row.get('tags') or {}).get('ServiceRole') == 'dependency' for row in by_type('aws_instance'))
        if not allowed:
            raise ValueError('Missing exact full-topology ownership or parent linkage: ' + change['address'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['preflight', 'inventory', 'plan-destroy', 'apply-destroy'])
    parser.add_argument('--infra', type=Path, default=Path(__file__).resolve().parents[3] / 'infra/full')
    parser.add_argument('--profile')
    parser.add_argument('--expected-account', required=True)
    parser.add_argument('--session', required=True)
    parser.add_argument('--plan', type=Path)
    parser.add_argument('--var-file', type=Path, help='Exact private inputs used to plan this full topology')
    parser.add_argument('--approved-plan-sha256')
    parser.add_argument('--accept-dependency-data-loss', action='store_true')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.profile:
        os.environ['AWS_PROFILE'] = args.profile
    module = args.infra.resolve()
    if module.name != 'full' or module.parent.name != 'infra':
        raise SystemExit('Select the dedicated infra/full root')
    if args.output.exists():
        raise SystemExit('Use a new private evidence directory')

    def execute(command):
        result = subprocess.run(command, capture_output=True, text=True, stdin=subprocess.DEVNULL,
                                timeout=120 if command[0] == 'aws' else None)
        if result.returncode:
            raise RuntimeError('Operation failed: ' + ' '.join(command[:2]))
        return result.stdout

    def terraform(*parts):
        return execute(['terraform', '-chdir=' + str(module)] + list(parts))

    owner = json.loads(terraform('output', '-json', 'ownership'))
    manifest = json.loads(terraform('output', '-json', 'comparison_manifest'))
    if owner['project'] != 'auctionhouse' or owner['topology'] != 'full' or owner['session_id'] != args.session or owner['account_id'] != args.expected_account:
        raise SystemExit('Full state ownership mismatch')
    backend_file = module / '.terraform/terraform.tfstate'
    backend = json.loads(backend_file.read_text()).get('backend') or {}
    if backend.get('type') != 's3' or backend.get('config', {}).get('key') != manifest['stateIsolation']['backendKey'] or not backend.get('config', {}).get('encrypt') or not backend.get('config', {}).get('use_lockfile'):
        raise SystemExit('Actual initialized backend must match the declared isolated encrypted/locked S3 key')
    aws_prefix = ['aws', '--region', owner['region'], '--output', 'json', '--no-cli-pager'] + (['--profile', args.profile] if args.profile else [])

    def aws(*parts):
        return json.loads(execute(aws_prefix + list(parts)) or '{}')

    if aws('sts', 'get-caller-identity')['Account'] != args.expected_account:
        raise SystemExit('AWS identity mismatch')
    args.output.mkdir(parents=True)

    def inventory(label):
        tags = aws('resourcegroupstaggingapi', 'get-resources', '--tag-filters',
                   'Key=Project,Values=auctionhouse', 'Key=SessionId,Values=' + args.session, 'Key=Topology,Values=full')
        (args.output / (label + '-tagged-resources.json')).write_text(json.dumps(tags, indent=2) + '\n')
        if label == 'before':
            (args.output / 'state-resource-inventory.json').write_text(terraform('output', '-json', 'resource_inventory'))
            (args.output / 'comparison-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')

    if args.command == 'preflight':
        print('PASS: selected account, full state, exact encrypted locked backend')
        return
    inventory('before')
    if args.command == 'inventory':
        return
    if not args.plan:
        raise SystemExit('An explicit private saved-plan path is required')
    saved = args.plan.resolve()
    if args.command == 'plan-destroy':
        if not args.var_file or not args.var_file.is_file():
            raise SystemExit('Supply the exact private --var-file for a noninteractive destroy plan')
        if saved.exists():
            raise SystemExit('Refusing to overwrite a saved plan')
        saved.parent.mkdir(parents=True, exist_ok=True)
        terraform('plan', '-destroy', '-input=false', '-var-file=' + str(args.var_file.resolve()), '-out=' + str(saved))
        print('Review exact saved plan SHA256: ' + hashlib.sha256(saved.read_bytes()).hexdigest())
        return
    if not args.approved_plan_sha256 or hashlib.sha256(saved.read_bytes()).hexdigest() != args.approved_plan_sha256.lower():
        raise SystemExit('Exact saved plan SHA256 approval is required')
    plan = json.loads(terraform('show', '-json', str(saved)))
    validate_destroy(plan, args.session, args.accept_dependency_data_loss)
    terraform('apply', '-input=false', str(saved))
    inventory('after')
    print('Saved teardown applied. Inspect residual SSM secrets, ECR images, final RDS/EBS snapshots and external backend/certificate separately; tag inventory alone is not proof of zero cost.')


if __name__ == '__main__':
    main()
