import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('thin_release', Path(__file__).resolve().parents[1] / 'send-release-command.py')
RELEASE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RELEASE)
DOCUMENT = 'auctionhouse-fixture-release'
INSTANCE = 'i-' + 'a' * 17
DIGEST = 'sha256:' + 'b' * 64
COMMAND_ID = '12345678-1234-1234-1234-123456789abc'


class ReleaseCommandContracts(unittest.TestCase):
    def test_missing_symbolic_zero_and_malformed_versions_cannot_invoke_aws(self):
        for version in [None, '', '$DEFAULT', '$LATEST', '0', '-1', '01', '1; echo bad', '1\n']:
            with patch.object(RELEASE.subprocess, 'run') as invoke, tempfile.TemporaryDirectory() as directory:
                with self.assertRaises(ValueError):
                    RELEASE.send(DOCUMENT, version, INSTANCE, DIGEST, 'ap-south-1', Path(directory) / 'command.json')
                invoke.assert_not_called()

    def test_command_binds_exact_version_instance_digest_and_fixed_parameters(self):
        command = RELEASE.command_arguments(DOCUMENT, '17', INSTANCE, DIGEST, 'ap-south-1')
        self.assertEqual(command[command.index('--document-version') + 1], '17')
        self.assertEqual(command[command.index('--instance-ids') + 1], INSTANCE)
        self.assertEqual(json.loads(command[command.index('--parameters') + 1]),
                         {'Mode': ['deploy'], 'Digest': [DIGEST], 'Compatibility': ['schema-compatible']})
        self.assertNotIn('$DEFAULT', command)
        self.assertNotIn('$LATEST', command)
        self.assertIn('--no-cli-pager', command)

    def test_precredential_validation_never_calls_aws(self):
        with patch.object(sys, 'argv', ['release', '--document-version', '17', '--validate-only']), patch.object(RELEASE.subprocess, 'run') as invoke:
            RELEASE.main()
            invoke.assert_not_called()

    def test_missing_evidence_directory_fails_before_sending(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(RELEASE.subprocess, 'run') as invoke:
            with self.assertRaises(ValueError):
                RELEASE.send(DOCUMENT, '17', INSTANCE, DIGEST, 'ap-south-1', Path(directory) / 'missing/command.json')
            invoke.assert_not_called()

    def test_selected_version_is_sent_once_and_retained_in_evidence(self):
        response = {'Command': {'CommandId': COMMAND_ID, 'DocumentName': DOCUMENT, 'DocumentVersion': '17'}}
        result = subprocess.CompletedProcess([], 0, json.dumps(response), '')
        with tempfile.TemporaryDirectory() as directory, patch.object(RELEASE.subprocess, 'run', return_value=result) as invoke:
            output = Path(directory) / 'command.json'
            self.assertEqual(RELEASE.send(DOCUMENT, '17', INSTANCE, DIGEST, 'ap-south-1', output), COMMAND_ID)
            invoke.assert_called_once()
            self.assertEqual(invoke.call_args.args[0][invoke.call_args.args[0].index('--document-version') + 1], '17')
            self.assertEqual(json.loads(output.read_text())['documentVersion'], '17')
            self.assertEqual(invoke.call_args.kwargs['stdin'], subprocess.DEVNULL)

    def test_mismatched_response_does_not_create_success_evidence(self):
        for change in [{'DocumentVersion': '18'}, {'DocumentName': 'AWS-RunShellScript'}, {'CommandId': 'invalid'}]:
            actual = dict(CommandId=COMMAND_ID, DocumentName=DOCUMENT, DocumentVersion='17')
            actual.update(change)
            result = subprocess.CompletedProcess([], 0, json.dumps({'Command': actual}), '')
            with tempfile.TemporaryDirectory() as directory, patch.object(RELEASE.subprocess, 'run', return_value=result):
                output = Path(directory) / 'command.json'
                with self.assertRaises(RuntimeError):
                    RELEASE.send(DOCUMENT, '17', INSTANCE, DIGEST, 'ap-south-1', output)
                self.assertFalse(output.exists())

    def test_unsafe_or_mutable_resource_inputs_are_rejected(self):
        for values in [('AWS-RunShellScript', INSTANCE, DIGEST, 'ap-south-1'),
                       (DOCUMENT, '*', DIGEST, 'ap-south-1'), (DOCUMENT, INSTANCE, 'latest', 'ap-south-1')]:
            with self.assertRaises(ValueError):
                RELEASE.command_arguments(values[0], '17', *values[1:])


if __name__ == '__main__':
    unittest.main()
