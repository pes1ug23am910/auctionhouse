"""Local contracts only: command doubles do not establish AWS deployment behavior."""
from __future__ import annotations

import base64
import gzip
import importlib.util
import io
import json
import os
from pathlib import Path
import runpy
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[4]
FULL = ROOT / 'ops/cloud/full'
BASH = Path('C:/Program Files/Git/bin/bash.exe') if os.name == 'nt' else Path(shutil.which('bash') or '/not-found')


def load_inspector():
    spec = importlib.util.spec_from_file_location('full_deployment_inspector', FULL / 'inspect-runtime.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class InspectionContracts(unittest.TestCase):
    def test_request_counts_group_only_named_application_auction_routes(self):
        text = '\n'.join([
            'http_server_request_duration_seconds_count{service_instance_id="app-0",http_route="/api/auctions",status="200"} 4',
            'http_server_request_duration_seconds_count{http_route="/api/auctions/{id}/bids",service_instance_id="app-0"} 2e1',
            'http_server_request_duration_seconds_count{service_instance_id="app-1",http_route="/api/auctions/{id}"} 7 123456',
            'http_server_request_duration_seconds_count{service_instance_id="app-1",http_route="/actuator/health"} 100',
            'http_server_request_duration_seconds_count{service_instance_id="foreign",http_route="/api/auctions"} 100',
            'http_server_request_duration_seconds_sum{service_instance_id="app-0",http_route="/api/auctions"} 200',
        ])
        module = load_inspector()
        with patch.object(module.urllib.request, 'urlopen', return_value=io.BytesIO(text.encode())):
            self.assertEqual(module.request_counts(), {'app-0': 24.0, 'app-1': 7.0})

    def test_missing_or_failed_metric_export_is_unknown_not_zero(self):
        module = load_inspector()
        with patch.object(module.urllib.request, 'urlopen', side_effect=TimeoutError):
            self.assertIsNone(module.request_counts())
        with patch.object(module.urllib.request, 'urlopen', return_value=io.BytesIO(b'up 1\n')):
            self.assertIsNone(module.request_counts())

    def test_inspection_allowlist_excludes_secret_environment_values(self):
        module = load_inspector()
        raw = [{
            'State': {'Running': True},
            'Image': 'sha256:' + '1' * 64,
            'Config': {'Image': 'registry.example/app@sha256:' + '2' * 64,
                       'Env': ['AUCTIONHOUSE_DB_POOL_SIZE=8', 'AUCTIONHOUSE_DB_PASSWORD=private-db-fixture',
                               'OIDC_CLIENT_SECRET=private-oidc-fixture', 'AUCTIONHOUSE_DEMO_PASSWORD_HASH=private-demo-fixture']},
            'HostConfig': {'Memory': 1024, 'NanoCpus': 1000, 'ReadonlyRootfs': True},
        }]
        completed = subprocess.CompletedProcess([], 0, json.dumps(raw), '')
        with patch.object(module.subprocess, 'run', return_value=completed) as command:
            result = module.inspect('auctionhouse-app')
        self.assertEqual(command.call_args.args[0], ['docker', 'inspect', 'auctionhouse-app'])
        self.assertEqual(result['runtimeEnvironment'], {'AUCTIONHOUSE_DB_POOL_SIZE': '8'})
        self.assertFalse(result['demoCredentialsAbsent'])
        self.assertNotIn('private-', json.dumps(result))

    def test_missing_container_is_not_reported_healthy(self):
        module = load_inspector()
        with patch.object(module.subprocess, 'run', return_value=subprocess.CompletedProcess([], 1, '', 'missing')):
            self.assertEqual(module.inspect('auctionhouse-app'), {'name': 'auctionhouse-app', 'present': False})


class SecretAndBootstrapContracts(unittest.TestCase):
    def runtime(self, config):
        temporary = tempfile.TemporaryDirectory(prefix='auctionhouse-secret-test-')
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        input_file = root / 'input.json'
        output = root / 'output'
        input_file.write_text(json.dumps(config), encoding='utf-8')
        chmods, owners = [], []
        with patch.object(sys, 'argv', ['runtime-secrets.py', str(input_file), str(output)]), \
             patch.object(Path, 'chmod', lambda path, mode: chmods.append((path, mode))), \
             patch.object(os, 'chown', lambda path, uid, gid: owners.append((path, uid, gid)), create=True), \
             patch('sys.stdout', new_callable=io.StringIO) as stdout, patch('sys.stderr', new_callable=io.StringIO) as stderr:
            error = None
            try:
                runpy.run_path(str(FULL / 'runtime-secrets.py'), run_name='__main__')
            except SystemExit as failure:
                error = str(failure)
        return output, chmods, owners, stdout.getvalue() + stderr.getvalue(), error

    def valid(self):
        return {'spring.datasource.password': 'private-db-fixture',
                'spring.security.oauth2.client.registration.keycloak.client-id': 'fixture-public-client',
                'spring.security.oauth2.client.provider.keycloak.issuer-uri': 'https://issuer.example/realm'}

    def test_public_pkce_secret_files_use_restricted_permissions_and_no_logs(self):
        output, modes, owners, logs, error = self.runtime(self.valid())
        self.assertIsNone(error)
        self.assertEqual(logs, '')
        self.assertEqual({p.name: p.read_text() for p in output.iterdir()}, self.valid())
        self.assertIn((output, 0o755), modes)
        for path in output.iterdir():
            self.assertIn((path, 0o400), modes)
            self.assertIn((path, 10001, 10001), owners)

    def test_unknown_confidential_client_secret_is_rejected_before_output(self):
        config = self.valid()
        config['spring.security.oauth2.client.registration.keycloak.client-secret'] = 'private-unexpected-fixture'
        output, _, _, logs, error = self.runtime(config)
        self.assertFalse(output.exists())
        self.assertEqual(logs, '')
        self.assertEqual(error, 'Invalid runtime secret configuration; no values logged')

    def test_missing_password_and_plain_http_issuer_fail_closed(self):
        for alter in [lambda data: data.pop('spring.datasource.password'),
                      lambda data: data.update({'spring.security.oauth2.client.provider.keycloak.issuer-uri': 'http://issuer.example'})]:
            with self.subTest(alter=alter):
                config = self.valid(); alter(config)
                output, _, _, logs, error = self.runtime(config)
                self.assertFalse(output.exists())
                self.assertEqual(logs, '')
                self.assertIsNotNone(error)

    def run_template(self, assets):
        temporary = tempfile.TemporaryDirectory(prefix='auctionhouse-bundle-test-')
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name) / 'bundle'
        root.mkdir()
        template = (ROOT / 'infra/full/host-init.sh.tftpl').read_text(encoding='utf-8')
        body = template.split("python3 - <<'PY'\n", 1)[1].split('\nPY\n', 1)[0]
        bundle = base64.b64encode(gzip.compress(json.dumps(assets).encode())).decode()
        body = body.replace("'/opt/auctionhouse/full'", repr(str(root))).replace('${bundle}', bundle)
        modes = []
        with patch.object(Path, 'chmod', lambda path, mode: modes.append((path, mode))):
            exec(compile(body, 'host-init-bundle-fixture', 'exec'), {})
        return root, modes

    def test_nested_observability_is_readable_without_exposing_private_host_config(self):
        root, modes = self.run_template({'host-config.json': '{}', 'bootstrap.sh': '#!/bin/sh\n',
                                        'observability/grafana/provisioning/datasources/source.yaml': 'apiVersion: 1\n'})
        self.assertIn((root / 'host-config.json', 0o600), modes)
        self.assertIn((root / 'bootstrap.sh', 0o700), modes)
        config = root / 'observability/grafana/provisioning/datasources/source.yaml'
        self.assertIn((config, 0o644), modes)
        parent = config.parent
        while parent != root:
            self.assertIn((parent, 0o755), modes)
            parent = parent.parent

    def test_bundled_path_escape_is_rejected(self):
        with self.assertRaisesRegex(SystemExit, 'Invalid bundled path'):
            self.run_template({'../outside.json': 'never-written'})

    @unittest.skipUnless(importlib.util.find_spec("duckdb"), "Run this guard test with the locked warehouse environment")
    def test_remote_warehouse_export_requires_existing_cut_and_verified_tls(self):
        script = ROOT / 'warehouse/tools/capture_local_cut.py'
        with tempfile.TemporaryDirectory(prefix='auctionhouse-export-test-') as directory:
            for overrides in [dict(PGSSLMODE='require', PGSSLROOTCERT='ca.pem', PGPASSFILE='private'),
                              dict(PGSSLMODE='verify-full', PGSSLROOTCERT='', PGPASSFILE='private'),
                              dict(PGSSLMODE='verify-full', PGSSLROOTCERT='ca.pem', PGPASSFILE='')]:
                with self.subTest(overrides=overrides):
                    command = [sys.executable, str(script), '--psql', '--cut', '11111111-1111-1111-1111-111111111111',
                               '--output', str(Path(directory) / 'export')]
                    process = subprocess.run(command, env={**os.environ, **overrides}, text=True, capture_output=True, timeout=20)
                    self.assertNotEqual(process.returncode, 0)
                    self.assertIn('Remote export requires an existing cut', process.stderr)
                    self.assertFalse((Path(directory) / 'export').exists())


@unittest.skipUnless(BASH.exists(), 'A local Bash executable is required for command-double release tests')
class ReleaseContracts(unittest.TestCase):
    def release(self, existing, fail_smoke=False, fail_start=False):
        temporary = tempfile.TemporaryDirectory(prefix='auctionhouse-release-test-')
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name).resolve()
        # Every absolute host path is redirected into this verified temporary fixture.
        self.assertTrue(root.is_relative_to(Path(tempfile.gettempdir()).resolve()))
        posix = root.as_posix()
        if os.name == 'nt':
            posix = '/' + posix[0].lower() + posix[2:]
        full = root / 'opt'; full.mkdir()
        (root / 'state').mkdir(); (root / 'run').mkdir()
        secret = root / 'run/auctionhouse-full-runtime/config.prior'; secret.mkdir(parents=True)
        state = root / 'state/current.json'
        old = {'appDigest': 'sha256:' + 'a' * 64, 'gatewayDigest': 'sha256:' + 'b' * 64, 'warehouseDigest': 'sha256:' + 'c' * 64}
        if existing: state.write_text(json.dumps(old), encoding='utf-8')
        (full / 'host-config.json').write_text('{}', encoding='utf-8')
        (full / 'smoke.sh').write_text('#!/bin/bash\nsmoke_stub\n', encoding='utf-8', newline='\n')
        (full / 'smoke.sh').chmod(0o700)
        body = (FULL / 'release.sh').read_text(encoding='utf-8')
        body = body.replace('[[ $EUID == 0 ]]', '[[ 1 == 1 ]]')
        body = body.replace('/opt/auctionhouse/full', posix + '/opt').replace('/var/lib/auctionhouse/full', posix + '/state').replace('/run/', posix + '/run/')
        body = body.replace(posix + '/run/secrets', '/run/secrets')
        prelude = r'''#!/bin/bash
set -euo pipefail
export MSYS_NO_PATHCONV=1
log="$FIXTURE_ROOT/commands.log"
flock() { return 0; }
aws() { if [[ $1 == ecr ]]; then echo fixture-token; else echo '{}'; fi; }
python3() { mkdir -p "$3"; echo fixture-secret > "$3/password"; }
smoke_stub() { echo smoke >> "$log"; [[ $FAIL_SMOKE != 1 || $(cat "$FIXTURE_ROOT/active-image") != *dddddddd* ]]; }
export -f smoke_stub
curl() { return 0; }
jq() {
  if [[ $1 == -n ]]; then
    printf '{"appDigest":"%s","gatewayDigest":"%s","warehouseDigest":"%s"}\n' "$4" "$7" "${10}"
    return
  fi
  case "$2" in
    .role) echo app ;; .region) echo region-fixture ;; .parameterPrefix) echo /auctionhouse/fixture/full ;;
    .dbHost) echo database.example ;; .repositories.app) echo registry.example/app ;; .repositories.gateway) echo registry.example/gateway ;;
    .repositories.warehouse) echo registry.example/warehouse ;; .dependencyAddress) echo 10.74.1.10 ;; .runtimeSettings.poolSize) echo 8 ;;
    .logGroups.app) echo /fixture/logs ;; .ordinal) echo 0 ;; .origin) echo https://auction.example ;;
    .appDigest) echo sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa ;;
    .gatewayDigest) echo sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb ;;
    .warehouseDigest) echo sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc ;;
    *) return 1 ;;
  esac
}
docker() {
  printf '%s\n' "$*" >> "$log"
  case "$1" in
    login) cat >/dev/null ;;
    inspect) [[ $EXISTING == 1 ]] && echo "$FIXTURE_ROOT/run/auctionhouse-full-runtime/config.prior" || return 1 ;;
    run)
      if [[ $* == *'--name auctionhouse-app '* ]]; then
        printf '%s\n' "${!#}" > "$FIXTURE_ROOT/active-image"
        [[ $FAIL_START != 1 || ${!#} != *dddddddd* ]] || return 1
      fi ;;
  esac
}
export log FIXTURE_ROOT FAIL_SMOKE
'''
        script = full / 'fixture-release.sh'
        script.write_text(prelude + '\n' + body, encoding='utf-8', newline='\n')
        environment = {**os.environ, 'FIXTURE_ROOT': posix, 'EXISTING': str(int(existing)),
                       'FAIL_SMOKE': str(int(fail_smoke)), 'FAIL_START': str(int(fail_start)), 'MSYS_NO_PATHCONV': '1'}
        process = subprocess.run([str(BASH), str(script), 'deploy', 'sha256:' + 'd' * 64,
                                  'sha256:' + 'e' * 64, 'sha256:' + 'f' * 64,
                                  '11111111-1111-1111-1111-111111111111', 'schema-compatible'],
                                 env=environment, text=True, capture_output=True, timeout=30)
        log = (root / 'commands.log').read_text() if (root / 'commands.log').exists() else ''
        after = json.loads(state.read_text()) if state.exists() else None
        return process, log, after, old, posix

    def test_failed_candidate_restores_prior_images_and_verified_secret_mount(self):
        process, log, after, old, root = self.release(True, fail_smoke=True)
        self.assertEqual(process.returncode, 1, process.stderr)
        self.assertEqual(after, old)
        self.assertIn('Previous verified application pair restored', process.stdout)
        launches = [line for line in log.splitlines() if line.startswith('run ') and '--name auctionhouse-app ' in line]
        self.assertEqual(len(launches), 2, log)
        self.assertIn('registry.example/app@' + old['appDigest'], launches[-1])
        self.assertIn(root + '/run/auctionhouse-full-runtime/config.prior:/run/secrets:ro', launches[-1])

    def test_immediate_candidate_start_failure_also_restores_previous_release(self):
        process, log, after, old, _ = self.release(True, fail_start=True)
        self.assertEqual(process.returncode, 1, process.stderr)
        self.assertEqual(after, old)
        self.assertIn('Previous verified application pair restored', process.stdout)
        self.assertEqual(sum('--name auctionhouse-app ' in line for line in log.splitlines()), 2)

    def test_success_records_only_the_verified_candidate_pair(self):
        process, log, after, _, _ = self.release(True)
        self.assertEqual(process.returncode, 0, process.stderr)
        self.assertEqual(after, {'appDigest': 'sha256:' + 'd' * 64,
                                 'gatewayDigest': 'sha256:' + 'e' * 64,
                                 'warehouseDigest': 'sha256:' + 'f' * 64})
        self.assertIn('Verified app release', process.stdout)
        self.assertEqual(sum('--name auctionhouse-app ' in line for line in log.splitlines()), 1)

    def test_first_release_failure_removes_candidate_and_writes_no_success_record(self):
        process, log, after, _, _ = self.release(False, fail_smoke=True)
        self.assertEqual(process.returncode, 1, process.stderr)
        self.assertIsNone(after)
        self.assertNotIn('Previous verified application pair restored', process.stdout)
        self.assertEqual(log.splitlines()[-1], 'rm -f auctionhouse-gateway auctionhouse-app')


if __name__ == '__main__':
    unittest.main(verbosity=2)
