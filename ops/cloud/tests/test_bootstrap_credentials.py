"""Bootstrap credential input contracts; every external command is a local double."""
import contextlib
import io
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import unittest
from unittest.mock import patch

CLOUD = Path(__file__).resolve().parents[1]
ACCOUNT = '111111111111'
ENV_NAME = 'AUCTIONHOUSE_MASTER_SECRET_JSON'
MASTER = {'username': 'ah_bootstrap', 'password': 'synthetic-master-password-never-log'}
STAGED = 'fixture_' + 'a' * 40
MISSING = object()


class BootstrapCredentialContracts(unittest.TestCase):
    def run_bootstrap(self, kind, *, supplied=MISSING, external=True, account=ACCOUNT, fail_psql=False, env_name=ENV_NAME):
        script = CLOUD / ('bootstrap-database.py' if kind == 'database' else 'full/bootstrap-extra.py')
        source = compile(script.read_text(encoding='utf-8'), str(script), 'exec')
        argv = [str(script), '--region', 'ap-south-1', '--account-id', ACCOUNT,
                '--session', 'fixture', '--expires-at', '2099-01-01T00:00:00Z',
                '--master-secret-arn', 'arn:aws:secretsmanager:ap-south-1:' + ACCOUNT + ':secret:fixture-abcdef',
                '--db-host', 'db.example.invalid', '--ca-file', 'fixture-ca.pem']
        if kind == 'database':
            argv += ['--issuer', 'https://issuer.example.invalid', '--client-id', 'fixture']
        if external:
            argv += ['--master-secret-env', env_name]
        environment = {} if supplied is MISSING else {ENV_NAME: supplied}
        calls, stdout, stderr = [], io.StringIO(), io.StringIO()
        native_os_name = os.name

        def command(args, **kwargs):
            # Exercise the Linux-only scripts on either test platform without executing children.
            os.name = native_os_name
            calls.append((args, kwargs))
            if external:
                self.assertNotIn(ENV_NAME, os.environ)
                self.assertNotIn(ENV_NAME, kwargs.get('env', {}))
            self.assertNotIn(MASTER['password'], str(args))
            self.assertNotIn(MASTER['password'], kwargs.get('input') or '')
            self.assertTrue(kwargs['capture_output'])
            if args[0] == 'psql':
                self.assertEqual(kwargs['env']['PGPASSWORD'], MASTER['password'])
                self.assertEqual(kwargs['env']['PGUSER'], MASTER['username'])
                self.assertEqual(kwargs['env']['PGSSLMODE'], 'verify-full')
                return subprocess.CompletedProcess(args, int(fail_psql), '', MASTER['password'] if fail_psql else '')
            if 'get-caller-identity' in args:
                payload = {'Account': account}
            elif 'get-secret-value' in args:
                self.assertFalse(external, 'External credentials must never trigger a fallback retrieval')
                payload = {'SecretString': json.dumps(MASTER)}
            elif 'get-parameter' in args:
                name = args[args.index('--name') + 1]
                value = json.dumps({'spring.datasource.password': STAGED}) if name.endswith('/runtime') else STAGED
                payload = {'Parameter': {'Value': value}}
            else:
                raise AssertionError('Unexpected bootstrap operation: ' + repr(args))
            return subprocess.CompletedProcess(args, 0, json.dumps(payload), '')

        failure = None
        with patch.dict(os.environ, environment, clear=True), patch.object(sys, 'argv', argv), \
             patch.object(os, 'name', 'posix'), patch.object(subprocess, 'run', side_effect=command), \
             patch.object(secrets, 'token_urlsafe', return_value=STAGED), \
             contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            try:
                exec(source, {'__name__': '__main__', '__file__': str(script)})
            except SystemExit as exc:
                failure = str(exc)
            self.assertNotIn(ENV_NAME, os.environ) if external and supplied is not MISSING else None
        output = stdout.getvalue() + stderr.getvalue() + (failure or '')
        self.assertNotIn(MASTER['password'], output)
        return calls, failure, output

    def test_resolved_master_input_reaches_only_psql_and_never_direct_retrieval(self):
        for kind in ['database', 'extra']:
            with self.subTest(kind=kind):
                calls, failure, output = self.run_bootstrap(kind, supplied=json.dumps(MASTER))
                self.assertIsNone(failure)
                self.assertEqual(sum(args[0] == 'psql' for args, _ in calls), 1)
                self.assertFalse(any('get-secret-value' in args for args, _ in calls))
                self.assertIn('complete', output)

    def test_missing_external_variable_fails_without_any_child_process(self):
        for kind in ['database', 'extra']:
            with self.subTest(kind=kind):
                calls, failure, _ = self.run_bootstrap(kind)
                self.assertIsNotNone(failure)
                self.assertEqual(calls, [])

    def test_empty_external_variable_name_cannot_select_operator_retrieval(self):
        for kind in ['database', 'extra']:
            with self.subTest(kind=kind):
                calls, failure, _ = self.run_bootstrap(kind, env_name='')
                self.assertIsNotNone(failure)
                self.assertEqual(calls, [])

    def test_invalid_external_input_never_falls_back_or_stages_credentials(self):
        invalid = ['', '{{resolve:secretsmanager:fixture:SecretString}}', 'not-json', 'null', '[]',
                   json.dumps({'username': 'ah_bootstrap'}), json.dumps(dict(MASTER, password='')),
                   json.dumps(dict(MASTER, password=12)), json.dumps(dict(MASTER, username='\x00')),
                   json.dumps(dict(MASTER, password='{{resolve:secretsmanager:fixture:SecretString:password}}'))]
        for kind in ['database', 'extra']:
            for value in invalid:
                with self.subTest(kind=kind, input_type=invalid.index(value)):
                    calls, failure, output = self.run_bootstrap(kind, supplied=value)
                    self.assertIsNotNone(failure)
                    self.assertTrue(all('get-caller-identity' in args for args, _ in calls))
                    self.assertNotIn('{{resolve:', output)

    def test_account_mismatch_precedes_any_secret_or_database_operation(self):
        for kind in ['database', 'extra']:
            for external in [True, False]:
                with self.subTest(kind=kind, external=external):
                    calls, failure, _ = self.run_bootstrap(kind, supplied=json.dumps(MASTER), external=external, account='222222222222')
                    self.assertIsNotNone(failure)
                    self.assertEqual(len(calls), 1)
                    self.assertIn('get-caller-identity', calls[0][0])

    def test_ordinary_operator_mode_keeps_captured_secret_retrieval(self):
        for kind in ['database', 'extra']:
            with self.subTest(kind=kind):
                calls, failure, _ = self.run_bootstrap(kind, external=False)
                self.assertIsNone(failure)
                self.assertEqual(sum('get-secret-value' in args for args, _ in calls), 1)

    def test_database_error_does_not_print_subprocess_secret_output(self):
        for kind in ['database', 'extra']:
            with self.subTest(kind=kind):
                calls, failure, _ = self.run_bootstrap(kind, supplied=json.dumps(MASTER), fail_psql=True)
                self.assertIsNotNone(failure)
                self.assertEqual(sum(args[0] == 'psql' for args, _ in calls), 1)


if __name__ == '__main__':
    unittest.main()
