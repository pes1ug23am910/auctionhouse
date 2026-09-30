"""Operator command doubles test rollback/teardown guards without contacting AWS."""
from __future__ import annotations

from copy import deepcopy
from datetime import datetime, timedelta, timezone
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

FULL = Path(__file__).resolve().parents[1]


def module(name):
    spec = importlib.util.spec_from_file_location('auctionhouse_full_' + name, FULL / (name + '.py'))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


def tagged(kind, name, extra=None):
    before = {'id': name, 'tags': {'Project': 'auctionhouse', 'SessionId': 'fixture', 'Topology': 'full'}}
    before.update(extra or {})
    return {'address': kind + '.' + name, 'mode': 'managed', 'type': kind,
            'change': {'actions': ['delete'], 'before': before, 'after': None}}


def untagged(kind, name, before):
    return {'address': kind + '.' + name, 'mode': 'managed', 'type': kind,
            'change': {'actions': ['delete'], 'before': before, 'after': None}}


class TeardownContracts(unittest.TestCase):
    def test_role_policy_attachment_cannot_delete_an_unreviewed_managed_policy(self):
        state = module('state')
        role = tagged('aws_iam_role', 'role', {'name': 'owned-role'})
        policy = untagged('aws_iam_role_policy_attachment', 'policy', {'role': 'owned-role',
                         'policy_arn': 'arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore'})
        state.validate_destroy({'resource_changes': [role, policy]}, 'fixture')
        policy['change']['before']['policy_arn'] = 'arn:aws:iam::aws:policy/AdministratorAccess'
        with self.assertRaises(ValueError):
            state.validate_destroy({'resource_changes': [role, policy]}, 'fixture')

    def state_main(self, approved_hash=None, backend_change=None):
        temporary = tempfile.TemporaryDirectory(prefix='auctionhouse-state-contract-')
        self.addCleanup(temporary.cleanup)
        directory = Path(temporary.name)
        infra = directory / 'infra/full'
        (infra / '.terraform').mkdir(parents=True)
        backend = {'backend': {'type': 's3', 'config': {'key': 'auctionhouse/fixture/full/terraform.tfstate',
                    'encrypt': True, 'use_lockfile': True}}}
        if backend_change:
            backend_change(backend)
        (infra / '.terraform/terraform.tfstate').write_text(json.dumps(backend), encoding='utf-8')
        plan = directory / 'approved.plan'
        plan.write_bytes(b'fixture exact saved binary plan')
        calls = []
        owner = {'project': 'auctionhouse', 'topology': 'full', 'session_id': 'fixture',
                 'account_id': '111111111111', 'region': 'us-east-1'}
        manifest = {'stateIsolation': {'backendKey': 'auctionhouse/fixture/full/terraform.tfstate'}}
        def command(args, **kwargs):
            calls.append(args)
            if args[0] == 'terraform':
                if 'output' in args:
                    data = {'ownership': owner, 'comparison_manifest': manifest, 'resource_inventory': {}}[args[-1]]
                elif 'show' in args:
                    data = {'resource_changes': [tagged('aws_vpc', 'fixture')]}
                elif 'apply' in args:
                    data = {}
                else:
                    raise AssertionError(args)
            elif 'get-caller-identity' in args:
                data = {'Account': owner['account_id']}
            elif 'get-resources' in args:
                data = {'ResourceTagMappingList': []}
            else:
                raise AssertionError(args)
            return subprocess.CompletedProcess(args, 0, json.dumps(data), '')
        state = module('state')
        argv = ['state.py', 'apply-destroy', '--infra', str(infra), '--profile', 'fixture',
                '--expected-account', owner['account_id'], '--session', 'fixture', '--plan', str(plan),
                '--approved-plan-sha256', approved_hash or hashlib.sha256(plan.read_bytes()).hexdigest(),
                '--output', str(directory / 'evidence')]
        return state, argv, command, calls, plan

    def test_apply_uses_only_exact_hash_approved_saved_plan(self):
        state, argv, command, calls, plan = self.state_main()
        with patch.object(sys, 'argv', argv), patch.object(state.subprocess, 'run', side_effect=command), patch('sys.stdout', new=io.StringIO()):
            state.main()
        applies = [call for call in calls if 'apply' in call]
        self.assertEqual(len(applies), 1)
        self.assertEqual(applies[0][-2:], ['-input=false', str(plan)])
        self.assertFalse(any('destroy' in call or '-auto-approve' in call for call in calls))
        self.assertTrue(all('--no-cli-pager' in c and c[c.index('--output') + 1] == 'json' for c in calls if c[0] == 'aws'))

    def test_wrong_saved_hash_or_unlocked_backend_stops_before_apply(self):
        for args in [{'approved_hash': '0' * 64}, {'backend_change': lambda b: b['backend']['config'].update(use_lockfile=False)}]:
            state, argv, command, calls, _ = self.state_main(**args)
            with patch.object(sys, 'argv', argv), patch.object(state.subprocess, 'run', side_effect=command):
                with self.assertRaises(SystemExit):
                    state.main()
            self.assertFalse(any('apply' in call for call in calls))


class DeploymentContracts(unittest.TestCase):
    def fixture(self, *, count=2, fail_deploy=None, fail_rollback=None, initial_release=True, alter=None):
        temporary = tempfile.TemporaryDirectory(prefix='auctionhouse-deploy-contract-')
        self.addCleanup(temporary.cleanup)
        directory = Path(temporary.name)
        apps = ['i-' + str(i) * 17 for i in range(1, count + 1)]
        dependency = 'i-' + '3' * 17
        old = {name + 'Digest': 'sha256:' + value * 64 for name, value in [('app', 'a'), ('gateway', 'b'), ('warehouse', 'c')]}
        desired = {name + 'Digest': 'sha256:' + value * 64 for name, value in [('app', 'd'), ('gateway', 'e'), ('warehouse', 'f')]}
        expiry = (datetime.now(timezone.utc) + timedelta(hours=2)).isoformat()
        owner = {'project': 'auctionhouse', 'sessionId': 'fixture', 'accountId': '111111111111', 'region': 'us-east-1', 'expiresAt': expiry}
        manifest = {'schemaVersion': 1, 'ownership': owner, 'applicationHostCount': count, 'applicationInstanceIds': apps,
                    'dependencyInstanceId': dependency, 'origin': 'https://auction.example.com',
                    'ecrRepositoryUrls': {kind: '111111111111.dkr.ecr.us-east-1.amazonaws.com/fixture-' + kind for kind in ['app', 'gateway', 'warehouse']},
                    'loadBalancer': {'targetGroupArn': 'app-group', 'gatewayTargetGroupArn': 'gateway-group', 'arn': 'fixture-alb'},
                    'releaseDocumentName': 'auctionhouse-fixture-full-release', 'inspectionDocumentName': 'auctionhouse-fixture-full-inspect'}
        (directory / 'manifest.json').write_text(json.dumps(manifest), encoding='utf-8')
        (directory / 'release.json').write_text(json.dumps(desired), encoding='utf-8')
        calls, sent = [], []
        active = dict.fromkeys(apps, old if initial_release else None)
        def tags(role=None):
            result = [{'Key': key, 'Value': value} for key, value in {'Project': 'auctionhouse', 'SessionId': 'fixture',
                      'Topology': 'full', 'ExpiresAt': expiry}.items()]
            return result + ([{'Key': 'ServiceRole', 'Value': role}] if role else [])
        def command(args, **kwargs):
            calls.append(args)
            service_index = next(i for i, value in enumerate(args) if value in ['sts', 'ec2', 'elbv2', 'ssm'])
            operation = args[service_index] + ':' + args[service_index + 1]
            value = lambda key: args[args.index(key) + 1]
            if operation == 'sts:get-caller-identity':
                data = {'Account': owner['accountId']}
            elif operation == 'ec2:describe-instances':
                data = {'Reservations': [{'Instances': [{'InstanceId': i, 'State': {'Name': 'running'},
                         'Tags': tags('dependency' if i == dependency else 'app')} for i in apps + [dependency]]}]}
            elif operation == 'elbv2:describe-tags':
                data = {'TagDescriptions': [{'Tags': tags()}]}
            elif operation == 'ssm:get-document':
                inspect = value('--name').endswith('-inspect')
                text = 'python3 /opt/auctionhouse/full/inspect-runtime.py' if inspect else '/opt/auctionhouse/full/release.sh "$SSM_Mode" "$SSM_AppDigest" "$SSM_GatewayDigest" "$SSM_WarehouseDigest" "$SSM_CutId" "$SSM_Compatibility"'
                data = {'DocumentVersion': '4', 'Content': json.dumps({'schemaVersion': '2.2', 'mainSteps': [
                        {'action': 'aws:runShellScript', 'name': 'inspect' if inspect else 'release', 'inputs': {'runCommand': [text]}}]})}
            elif operation == 'ssm:send-command':
                parameters = json.loads(value('--parameters')) if '--parameters' in args else None
                sent.append((value('--instance-ids'), parameters, value('--document-version')))
                data = {'Command': {'CommandId': str(len(sent))}}
            elif operation == 'ssm:get-command-invocation':
                instance, parameters, _ = sent[int(value('--command-id')) - 1]
                data = {'Status': 'Success', 'ResponseCode': 0}
                if parameters is None:
                    data['StandardOutputContent'] = json.dumps({'release': active[instance], 'readiness': active[instance] is not None,
                        'containers': [{'name': 'auctionhouse-' + kind, 'running': True, 'readOnlyRootFilesystem': True, 'demoCredentialsAbsent': True,
                            'imageReference': manifest['ecrRepositoryUrls'][kind] + '@' + active[instance][kind + 'Digest']}
                            for kind in ['app', 'gateway']] if active[instance] else []})
                else:
                    mode = parameters['Mode'][0]
                    if (mode == 'deploy' and instance == fail_deploy) or (mode == 'rollback' and instance == fail_rollback):
                        data.update(Status='Failed', ResponseCode=1)
                    elif mode in ['deploy', 'rollback']:
                        active[instance] = {key: parameters[key[0].upper() + key[1:]][0] for key in desired}
                    elif mode == 'deactivate':
                        active[instance] = None
            elif operation in ['elbv2:register-targets', 'elbv2:deregister-targets']:
                data = {}
            elif operation == 'elbv2:describe-target-health':
                data = {'TargetHealthDescriptions': [{'TargetHealth': {'State': 'healthy'}}]}
            else:
                raise AssertionError(args)
            if alter:
                alter(operation, data)
            return subprocess.CompletedProcess(args, 0, json.dumps(data), '')
        deploy = module('deploy')
        argv = ['deploy.py', '--manifest', str(directory / 'manifest.json'), '--release', str(directory / 'release.json'),
                '--profile', 'fixture', '--schema-compatible', '--output', str(directory / 'evidence')]
        return deploy, argv, command, calls, sent, directory, apps, active, old, desired

    def execute(self, fixture, final_url='https://auction.example.com/actuator/health/readiness'):
        deploy, argv, command = fixture[:3]
        response = io.BytesIO(b'{"status":"UP"}')
        response.status = 200
        response.geturl = lambda: final_url
        with patch.object(sys, 'argv', argv), patch.object(deploy.subprocess, 'run', side_effect=command), \
             patch.object(deploy.time, 'sleep'), patch.object(deploy.urllib.request, 'urlopen', return_value=response), \
             patch('sys.stdout', new=io.StringIO()):
            deploy.main()

    def test_success_pins_document_version_and_verifies_every_host(self):
        fixture = self.fixture()
        self.execute(fixture)
        _, _, _, calls, sent, directory, apps, active, _, desired = fixture
        self.assertTrue(all(version == '4' for _, _, version in sent))
        self.assertTrue(all(active[instance] == desired for instance in apps))
        self.assertTrue((directory / 'evidence/after.json').is_file())
        self.assertTrue(all('--no-cli-pager' in c and c[c.index('--output') + 1] == 'json' for c in calls))
        self.assertEqual(len([c for c in calls if 'register-targets' in c]), 4)

    def test_expired_manifest_and_non_origin_urls_stop_before_aws(self):
        for change in [lambda m: m['ownership'].update(expiresAt='2000-01-01T00:00:00Z'),
                       lambda m: m.update(origin='http://auction.example.com'),
                       lambda m: m.update(origin='https://name:secret@auction.example.com'),
                       lambda m: m.update(origin='https://auction.example.com/foreign'),
                       lambda m: m.update(origin='https://auction.example.com/'),
                       lambda m: m.update(origin='https://auction.example.com?token=private')]:
            fixture = self.fixture()
            path = fixture[5] / 'manifest.json'
            value = json.loads(path.read_text())
            change(value)
            path.write_text(json.dumps(value), encoding='utf-8')
            with self.assertRaises(SystemExit):
                self.execute(fixture)
            self.assertEqual(fixture[3], [])

    def test_expiry_tags_must_match_the_approved_manifest(self):
        def alteration(operation, data):
            if operation == 'ec2:describe-instances':
                for tag in data['Reservations'][0]['Instances'][0]['Tags']:
                    if tag['Key'] == 'ExpiresAt':
                        tag['Value'] = '2099-01-01T00:00:00Z'
        fixture = self.fixture(alter=alteration)
        with self.assertRaises(SystemExit):
            self.execute(fixture)
        self.assertEqual(fixture[4], [])

    def test_actual_artifact_or_security_drift_rolls_back_even_with_matching_release_record(self):
        for change in [lambda row: row.update(imageReference='registry.example/unreviewed@sha256:' + '0' * 64),
                       lambda row: row.update(readOnlyRootFilesystem=False),
                       lambda row: row.update(demoCredentialsAbsent=False)]:
            def alteration(operation, data):
                if operation == 'ssm:get-command-invocation' and 'StandardOutputContent' in data:
                    report = json.loads(data['StandardOutputContent'])
                    if report['release']['appDigest'] == 'sha256:' + 'd' * 64:
                        change(report['containers'][0])
                        data['StandardOutputContent'] = json.dumps(report)
            fixture = self.fixture(alter=alteration)
            with self.assertRaises(RuntimeError):
                self.execute(fixture)
            _, _, _, _, sent, directory, apps, active, old, _ = fixture
            self.assertTrue(all(active[instance] == old for instance in apps))
            self.assertFalse((directory / 'evidence/after.json').exists())
            self.assertEqual(json.loads((directory / 'evidence/rollback-status.json').read_text())['failed'], [])

    def test_public_smoke_does_not_accept_a_redirected_foreign_or_plain_http_health_response(self):
        for url in ['http://auction.example.com/actuator/health/readiness',
                    'https://foreign.example.com/actuator/health/readiness',
                    'https://auction.example.com/unrelated-health']:
            fixture = self.fixture()
            with self.assertRaises(RuntimeError):
                self.execute(fixture, final_url=url)
            _, _, _, _, _, directory, apps, active, old, _ = fixture
            self.assertTrue(all(active[instance] == old for instance in apps))
            self.assertFalse((directory / 'evidence/after.json').exists())


if __name__ == '__main__':
    unittest.main()
