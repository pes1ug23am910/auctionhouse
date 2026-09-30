"""Ownership and multi-host rollout counterexamples; all AWS commands are doubles."""
import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

FULL = Path(__file__).resolve().parents[1]


def load(name):
    spec = importlib.util.spec_from_file_location('full_' + name, FULL / (name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def resource(address, kind, values, tags=True):
    before = dict(values)
    if tags:
        before['tags_all'] = {'Project': 'auctionhouse', 'SessionId': 'fixture', 'Topology': 'full', **values.get('tags_all', {})}
    return {'address': address, 'type': kind, 'mode': 'managed', 'change': {'actions': ['delete'], 'before': before}}


class TeardownTests(unittest.TestCase):
    def setUp(self):
        self.validate = load('state').validate_destroy
        self.parents = [resource('aws_iam_role.host["app"]', 'aws_iam_role', {'name': 'owned-host'}),
                        resource('aws_subnet.public[0]', 'aws_subnet', {'id': 'subnet-owned', 'vpc_id': 'vpc-owned'}),
                        resource('aws_route_table.public', 'aws_route_table', {'id': 'route-owned', 'vpc_id': 'vpc-owned'}),
                        resource('aws_instance.app[0]', 'aws_instance', {'id': 'i-owned', 'tags_all': {'ServiceRole': 'app'}}),
                        resource('aws_lb_target_group.service["app"]', 'aws_lb_target_group', {'arn': 'tg-owned'})]

    def test_tagged_parents_allow_only_bound_untaggable_children(self):
        children = [resource('aws_iam_role_policy.host["app"]', 'aws_iam_role_policy', {'role': 'owned-host'}, False),
                    resource('aws_route_table_association.public[0]', 'aws_route_table_association', {'subnet_id': 'subnet-owned', 'route_table_id': 'route-owned'}, False),
                    resource('aws_lb_target_group_attachment.app[0]', 'aws_lb_target_group_attachment', {'target_id': 'i-owned', 'target_group_arn': 'tg-owned'}, False)]
        self.validate({'resource_changes': self.parents + children}, 'fixture')
        for child in children:
            bad = json.loads(json.dumps(child))
            before = bad['change']['before']
            before[next(iter(before))] = 'unowned'
            with self.assertRaises(ValueError):
                self.validate({'resource_changes': self.parents + [bad]}, 'fixture')

    def test_thin_tags_cannot_authorize_full_teardown(self):
        bad = resource('aws_instance.app', 'aws_instance', {'id': 'i-thin'})
        del bad['change']['before']['tags_all']['Topology']
        with self.assertRaises(ValueError):
            self.validate({'resource_changes': [bad]}, 'fixture')

    def test_create_or_replace_in_destroy_plan_is_rejected(self):
        bad = json.loads(json.dumps(self.parents[0]))
        bad['change']['actions'] = ['delete', 'create']
        with self.assertRaises(ValueError):
            self.validate({'resource_changes': [bad]}, 'fixture')

    def test_dependency_volume_loss_requires_explicit_acceptance(self):
        disk = resource('aws_ebs_volume.dependencies', 'aws_ebs_volume', {'id': 'vol-owned'})
        with self.assertRaises(ValueError):
            self.validate({'resource_changes': [disk]}, 'fixture')
        self.validate({'resource_changes': [disk]}, 'fixture', True)


class RolloutTests(unittest.TestCase):
    def scenario(self, first_release=False, rollback_failure=False, drifted_document=False):
        module = load('deploy')
        current = {'appDigest': 'sha256:' + 'a' * 64, 'gatewayDigest': 'sha256:' + 'b' * 64, 'warehouseDigest': 'sha256:' + 'c' * 64}
        candidate = {'appDigest': 'sha256:' + 'd' * 64, 'gatewayDigest': 'sha256:' + 'e' * 64, 'warehouseDigest': 'sha256:' + 'f' * 64}
        apps = ['i-00000000000000001', 'i-00000000000000002']
        dependency = 'i-00000000000000003'
        values = {instance: None if first_release else current.copy() for instance in apps}
        registered = {instance: True for instance in apps}
        commands = {}
        modes = []
        tags = [{'Key': 'Project', 'Value': 'auctionhouse'}, {'Key': 'SessionId', 'Value': 'fixture'}, {'Key': 'Topology', 'Value': 'full'}, {'Key': 'ExpiresAt', 'Value': '2099-01-01T00:00:00Z'}]
        manifest = {'schemaVersion': 1, 'ownership': {'project': 'auctionhouse', 'accountId': '123456789012', 'region': 'ap-south-1', 'sessionId': 'fixture', 'expiresAt': '2099-01-01T00:00:00Z'},
                    'applicationHostCount': 2, 'applicationInstanceIds': apps, 'dependencyInstanceId': dependency,
                    'loadBalancer': {'arn': 'alb', 'targetGroupArn': 'app-tg', 'gatewayTargetGroupArn': 'gateway-tg'},
                    'releaseDocumentName': 'auctionhouse-fixture-full-release', 'inspectionDocumentName': 'auctionhouse-fixture-full-inspect',
                    'origin': 'https://auction.example.invalid'}

        def result(body):
            return SimpleNamespace(returncode=0, stdout=json.dumps(body), stderr='')

        def fake(command, **_):
            self.assertEqual(command[:3], ['aws', '--region', 'ap-south-1'])
            service_index = next(index for index, item in enumerate(command) if item in ['sts', 'ec2', 'elbv2', 'ssm'])
            service, operation = command[service_index:service_index + 2]
            value = lambda option: command[command.index(option) + 1]
            if service == 'sts':
                return result({'Account': '123456789012'})
            if service == 'ec2':
                return result({'Reservations': [{'Instances': [{'InstanceId': instance, 'State': {'Name': 'running'},
                                                                'Tags': tags + [{'Key': 'ServiceRole', 'Value': 'dependency' if instance == dependency else 'app'}]}
                                                               for instance in apps + [dependency]]}]})
            if operation == 'describe-tags':
                return result({'TagDescriptions': [{'Tags': tags}]})
            if operation == 'get-document':
                inspection = value('--name').endswith('-inspect')
                fixed = 'python3 /opt/auctionhouse/full/inspect-runtime.py' if inspection else '/opt/auctionhouse/full/release.sh "$SSM_Mode" "$SSM_AppDigest" "$SSM_GatewayDigest" "$SSM_WarehouseDigest" "$SSM_CutId" "$SSM_Compatibility"'
                if drifted_document:
                    fixed += '; unexpected-command'
                return result({'DocumentVersion': '7', 'Content': json.dumps({'mainSteps': [{'action': 'aws:runShellScript', 'inputs': {'runCommand': [fixed]}}]})})
            if operation == 'send-command':
                self.assertEqual(value('--document-version'), '7')
                instance = value('--instance-ids')
                identifier = str(len(commands) + 1)
                if value('--document-name').endswith('-inspect'):
                    body = {'Status': 'Success', 'StandardOutputContent': json.dumps({'release': values[instance], 'readiness': True})}
                else:
                    parameters = json.loads(value('--parameters'))
                    mode = parameters['Mode'][0]
                    modes.append((instance, mode))
                    fail = (mode == 'deploy' and instance == apps[1]) or (rollback_failure and mode == 'rollback' and instance == apps[1])
                    body = {'Status': 'Failed' if fail else 'Success', 'ResponseCode': 1 if fail else 0}
                    if not fail and mode in ['deploy', 'rollback']:
                        values[instance] = {name: parameters[parameter][0] for name, parameter in [('appDigest', 'AppDigest'), ('gatewayDigest', 'GatewayDigest'), ('warehouseDigest', 'WarehouseDigest')]}
                    if mode == 'deactivate':
                        values[instance] = None
                commands[identifier] = body
                return result({'Command': {'CommandId': identifier}})
            if operation == 'get-command-invocation':
                return result(commands[value('--command-id')])
            if operation in ['register-targets', 'deregister-targets']:
                registered[value('--targets').split('=')[1]] = operation == 'register-targets'
                return result({})
            if operation == 'describe-target-health':
                return result({'TargetHealthDescriptions': [{'TargetHealth': {'State': 'healthy'}}]})
            raise AssertionError((service, operation))

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'manifest.json').write_text(json.dumps(manifest))
            (root / 'release.json').write_text(json.dumps(candidate))
            argv = ['deploy.py', '--manifest', str(root / 'manifest.json'), '--release', str(root / 'release.json'),
                    '--schema-compatible', '--output', str(root / 'evidence')]
            with patch.object(sys, 'argv', argv), patch.object(module.subprocess, 'run', side_effect=fake), patch.object(module.time, 'sleep'):
                with self.assertRaises((RuntimeError, SystemExit)):
                    module.main()
            rollback = root / 'evidence/rollback-status.json'
            report = json.loads(rollback.read_text()) if rollback.exists() else None
        return apps, values, registered, modes, report, current

    def test_second_host_failure_restores_every_previous_pair(self):
        apps, values, registered, modes, report, current = self.scenario()
        self.assertEqual([values[instance] for instance in apps], [current, current])
        self.assertTrue(all(registered.values()))
        self.assertEqual(report['failed'], [])
        self.assertEqual({instance for instance, mode in modes if mode == 'rollback'}, set(apps))

    def test_first_release_failure_deactivates_all_touched_hosts(self):
        apps, values, registered, modes, report, _ = self.scenario(first_release=True)
        self.assertTrue(all(value is None for value in values.values()))
        self.assertFalse(any(registered.values()))
        self.assertEqual({instance for instance, mode in modes if mode == 'deactivate'}, set(apps))
        self.assertEqual(report['failed'], [])

    def test_one_failed_rollback_does_not_skip_other_hosts(self):
        apps, values, registered, modes, report, current = self.scenario(rollback_failure=True)
        self.assertEqual(report['failed'], [apps[1]])
        self.assertEqual(values[apps[0]], current)
        self.assertTrue(registered[apps[0]])
        self.assertFalse(registered[apps[1]])

    def test_drifted_command_document_never_runs(self):
        _, _, _, modes, report, _ = self.scenario(drifted_document=True)
        self.assertEqual(modes, [])
        self.assertIsNone(report)


if __name__ == '__main__':
    unittest.main(verbosity=2)
