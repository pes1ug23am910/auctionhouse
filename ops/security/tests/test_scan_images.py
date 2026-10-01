import importlib.util
import base64
import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest
import zipfile
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('image_scan', Path(__file__).resolve().parents[1] / 'scan-images.py')
SCAN = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SCAN)
IMAGE = 'sha256:' + 'a' * 64
REVISION = 'b' * 40
CONFIG = {'architecture': 'amd64', 'os': 'linux', 'rootfs': {'diff_ids': ['sha256:' + 'c' * 64]}, 'config': {}}
CONFIG_BYTES = json.dumps(CONFIG).encode()
CONFIG_DIGEST = 'sha256:' + hashlib.sha256(CONFIG_BYTES).hexdigest()
INSPECTED = {'Id': IMAGE, 'Os': 'linux', 'Architecture': 'amd64', 'RootFS': {'Layers': CONFIG['rootfs']['diff_ids']}, 'Config': {},
             'Descriptor': {'digest': IMAGE, 'mediaType': 'application/vnd.oci.image.index.v1+json'}}


class ImageScanContracts(unittest.TestCase):
    def test_only_exact_unique_local_artifact_ids_are_accepted(self):
        self.assertEqual(SCAN.parse_images(['app=' + IMAGE]), {'app': IMAGE})
        for values in [['app=latest'], ['other=' + IMAGE], ['app=' + IMAGE, 'app=' + IMAGE], ['app=sha256:a']]:
            with self.assertRaises(ValueError):
                SCAN.parse_images(values)

    def test_mismatched_download_checksum_fails_before_extraction(self):
        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory)
            (cache / 'grype_0.119.0_windows_amd64.zip').write_bytes(b'tampered archive')
            with patch.object(SCAN.platform, 'system', return_value='Windows'), patch.object(SCAN.platform, 'machine', return_value='AMD64'):
                with self.assertRaisesRegex(RuntimeError, 'checksum mismatch'):
                    SCAN.install('grype', cache)
            self.assertFalse((cache / 'grype.exe').exists())

    def test_inherited_scanner_ignore_and_stale_database_settings_are_removed(self):
        with patch.dict(os.environ, {'GRYPE_ONLY_FIXED': 'true', 'GRYPE_DB_VALIDATE_AGE': 'false', 'SYFT_EXCLUDE': '/app/**'}):
            env = SCAN.clean_environment(Path('/fixture'))
        self.assertNotIn('GRYPE_ONLY_FIXED', env)
        self.assertNotIn('SYFT_EXCLUDE', env)
        self.assertEqual(env['GRYPE_DB_VALIDATE_AGE'], 'true')
        self.assertEqual(env['GRYPE_DB_AUTO_UPDATE'], 'false')

    def sbom(self):
        return {'source': {'metadata': {'imageID': CONFIG_DIGEST, 'userInput': IMAGE, 'config': base64.b64encode(CONFIG_BYTES).decode(),
                                       'env': {'PASSWORD': 'must-not-export'}, 'imageConfig': 'private-config'}},
                'artifacts': [{'id': '1', 'name': 'libc6', 'version': '2.0', 'type': 'deb',
                               'metadata': {'private': 'must-not-export'}, 'locations': [{'path': '/var/lib/dpkg/status'}]}],
                'distro': {'id': 'debian', 'name': 'Debian', 'versionID': '12'},
                'descriptor': {'configuration': {'private': 'must-not-export'}}}

    def test_inventory_retains_package_provenance_without_image_configuration(self):
        report = SCAN.sbom_report(self.sbom(), IMAGE, REVISION, INSPECTED)
        self.assertEqual(report['packages'][0]['name'], 'libc6')
        self.assertEqual(report['packages'][0]['locations'], ['/var/lib/dpkg/status'])
        self.assertNotIn('must-not-export', json.dumps(report))
        self.assertNotIn('private-config', json.dumps(report))
        self.assertEqual(report['imageId'], IMAGE)
        self.assertEqual(report['configDigest'], CONFIG_DIGEST)

    def test_wrong_image_or_missing_os_inventory_cannot_be_reported_as_success(self):
        for mutation in [lambda s: s['source']['metadata'].update(imageID='sha256:' + 'c' * 64),
                         lambda s: s.update(artifacts=[]), lambda s: s['artifacts'][0].update(type='npm'),
                         lambda s: s.update(distro={})]:
            sbom = self.sbom()
            mutation(sbom)
            with self.assertRaises(RuntimeError):
                SCAN.sbom_report(sbom, IMAGE, REVISION, INSPECTED)

    def test_high_critical_unknown_block_even_without_fixes_and_medium_is_retained(self):
        matches = [{'vulnerability': {'id': 'CVE-fixture-' + severity, 'severity': severity, 'fix': {'state': 'not-fixed', 'versions': []}},
                    'artifact': {'name': 'fixture', 'version': '1', 'type': 'deb'}, 'matchDetails': [{'type': 'exact-direct-match'}]}
                   for severity in ['Critical', 'High', 'Unknown', 'Medium', 'Low', 'Negligible']]
        suppressed = dict(matches[0], appliedIgnoreRules=[{'reason': 'distro package is not affected', 'namespace': 'fixture:distro'}])
        raw = {'source': {'type': 'image', 'target': {'imageID': IMAGE, 'userInput': IMAGE}}, 'matches': matches, 'ignoredMatches': [suppressed]}
        result = SCAN.vulnerability_report(raw, IMAGE, REVISION)
        self.assertEqual(result['blockingFindings'], 3)
        self.assertEqual(len(result['findings']), 6)
        self.assertEqual(result['matcherSuppressedCount'], 1)
        self.assertEqual(result['matcherSuppressed'][0]['appliedIgnoreRules'][0]['reason'], 'distro package is not affected')
        raw['source']['target']['imageID'] = 'wrong'
        with self.assertRaises(RuntimeError):
            SCAN.vulnerability_report(raw, IMAGE, REVISION)

    def test_missing_severity_is_unknown_and_blocks(self):
        raw = {'source': {'type': 'image', 'target': {'imageID': IMAGE, 'userInput': IMAGE}}, 'matches': [{'vulnerability': {'id': 'CVE-no-severity'}, 'artifact': {}}]}
        self.assertEqual(SCAN.vulnerability_report(raw, IMAGE, REVISION)['blockingFindings'], 1)

    def test_truncated_findings_array_does_not_pass(self):
        with self.assertRaises(RuntimeError):
            SCAN.vulnerability_report({'source': {'type': 'image', 'target': {'imageID': IMAGE, 'userInput': IMAGE}}}, IMAGE, REVISION)

    def test_standard_sbom_contains_components_without_private_configuration(self):
        result = SCAN.cyclonedx_report(SCAN.sbom_report(self.sbom(), IMAGE, REVISION, INSPECTED))
        self.assertEqual(result['bomFormat'], 'CycloneDX')
        self.assertEqual(result['specVersion'], '1.6')
        self.assertEqual(result['components'][0]['bom-ref'], '1')
        self.assertNotIn('must-not-export', json.dumps(result))

    def test_os_only_inventory_cannot_stand_in_for_language_package_coverage(self):
        inventory = SCAN.sbom_report(self.sbom(), IMAGE, REVISION, INSPECTED)
        for name in ('app', 'gateway', 'warehouse'):
            with self.assertRaises(RuntimeError):
                SCAN.coverage_report(name, inventory)

    def test_internal_matcher_suppression_remains_reviewable_without_project_waivers(self):
        match = {'vulnerability': {'id': 'CVE-fixture', 'severity': 'High'},
                 'artifact': {'name': 'libc6', 'version': '1', 'type': 'deb'},
                 'appliedIgnoreRules': [{'reason': 'distro reports fixed', 'namespace': 'debian:12'}]}
        report = SCAN.vulnerability_report({'source': {'type': 'image', 'target': {'imageID': IMAGE, 'userInput': IMAGE}}, 'matches': [], 'ignoredMatches': [match]}, IMAGE, REVISION)
        self.assertEqual(report['blockingFindings'], 0)
        self.assertEqual(report['matcherSuppressed'][0]['vulnerability']['severity'], 'High')
        self.assertEqual(report['matcherSuppressed'][0]['appliedIgnoreRules'][0]['reason'], 'distro reports fixed')

    def test_wrong_layer_chain_or_configuration_rejects_an_otherwise_valid_digest(self):
        for mutation in [lambda c: c['rootfs'].update(diff_ids=['sha256:' + 'd' * 64]),
                         lambda c: c['config'].update(Env=['UNREVIEWED=1']),
                         lambda c: c.update(architecture='arm64')]:
            sbom = self.sbom()
            config = json.loads(CONFIG_BYTES)
            mutation(config)
            raw = json.dumps(config).encode()
            sbom['source']['metadata'].update(config=base64.b64encode(raw).decode(), imageID='sha256:' + hashlib.sha256(raw).hexdigest())
            with self.assertRaises(RuntimeError):
                SCAN.sbom_report(sbom, IMAGE, REVISION, INSPECTED)

    def agent_sbom(self):
        return {'files': [{'location': {'path': '/opt/otel/opentelemetry-javaagent.jar'},
                           'digests': [{'algorithm': 'sha256', 'value': SCAN.AGENT_JAR_SHA256}]}]}

    def test_missing_duplicate_or_wrong_embedded_agent_cannot_use_official_supplement(self):
        for sbom in [{}, {'files': self.agent_sbom()['files'] * 2}, self.agent_sbom()]:
            if len(sbom.get('files', [])) == 1:
                sbom['files'][0]['digests'][0]['value'] = '0' * 64
            with self.assertRaises(RuntimeError):
                SCAN.verify_agent_jar(sbom)

    def test_agent_supplement_requires_verified_archive_and_expected_declared_components(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / ('otel-' + SCAN.AGENT_VERSION + '-SBOM.zip')
            archive.write_bytes(b'untrusted')
            with self.assertRaisesRegex(RuntimeError, 'checksum mismatch'):
                SCAN.scan_agent(self.agent_sbom(), 'grype', 'config', root, root, {}, root, IMAGE, REVISION)
            for count in [0, 92, 93]:
                declared = {'packages': [{'name': 'javaagent', 'versionInfo': SCAN.AGENT_VERSION}] * count}
                with zipfile.ZipFile(archive, 'w') as package:
                    package.writestr('sboms/opentelemetry-javaagent.spdx.json', json.dumps(declared))
                result = {'source': {'type': 'sbom-file', 'target': str(root / 'app.agent.spdx.json')},
                          'matches': [{'vulnerability': {'id': 'CVE-fixture', 'severity': 'High'}, 'artifact': {}}]}
                with patch.object(SCAN, 'AGENT_SBOM_SHA256', SCAN.sha256(archive)), patch.object(SCAN, 'execute', return_value=json.dumps(result)):
                    if count < 93:
                        with self.assertRaisesRegex(RuntimeError, 'component/version'):
                            SCAN.scan_agent(self.agent_sbom(), 'grype', 'config', root, root, {}, root, IMAGE, REVISION)
                    else:
                        summary = SCAN.scan_agent(self.agent_sbom(), 'grype', 'config', root, root, {}, root, IMAGE, REVISION)
                        self.assertEqual(summary['componentCount'], 93)
                        self.assertEqual(summary['blockingFindings'], 1)

    def test_release_revision_is_required_even_if_image_identity_matches(self):
        with self.assertRaises(RuntimeError):
            SCAN.validate_release_image(INSPECTED, IMAGE, REVISION, False)
        SCAN.validate_release_image(INSPECTED, IMAGE, REVISION, True)
        valid = dict(INSPECTED, Config={'Labels': {'org.opencontainers.image.revision': REVISION}})
        SCAN.validate_release_image(valid, IMAGE, REVISION, False)

    def test_nonempty_official_agent_report_with_null_locations_retains_blockers(self):
        identifiers = ['GHSA-cxp5-3px4-pw24', 'GHSA-wv8q-qhhj-9h54']
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / ('otel-' + SCAN.AGENT_VERSION + '-SBOM.zip')
            declared = {'packages': [{'name': 'javaagent', 'versionInfo': SCAN.AGENT_VERSION}] * 93}
            with zipfile.ZipFile(archive, 'w') as package:
                package.writestr('sboms/opentelemetry-javaagent.spdx.json', json.dumps(declared))
            raw = {'source': {'type': 'sbom-file', 'target': str(root / 'app.agent.spdx.json')},
                   'matches': [{'vulnerability': {'id': identifier, 'severity': 'High',
                                 'fix': {'state': 'fixed', 'versions': ['2.22.3']}},
                                'artifact': {'name': 'jackson-databind', 'version': '2.22.2',
                                             'type': 'java-archive', 'locations': None},
                                'matchDetails': [{'type': 'exact-direct-match'}]}
                               for identifier in identifiers]}
            with patch.object(SCAN, 'AGENT_SBOM_SHA256', SCAN.sha256(archive)), patch.object(SCAN, 'execute', return_value=json.dumps(raw)):
                summary = SCAN.scan_agent(self.agent_sbom(), 'grype', 'config', root, root, {}, root, IMAGE, REVISION)
            saved = json.loads((root / 'app.agent.vulnerabilities.json').read_text(encoding='utf-8'))
            self.assertEqual(summary['blockingFindings'], 2)
            self.assertEqual(summary['severityCounts'], {'High': 2})
            self.assertEqual(summary['findingsSha256'], SCAN.sha256(root / 'app.agent.vulnerabilities.json'))
            self.assertEqual([row['vulnerability']['id'] for row in saved['findings']], identifiers)
            self.assertTrue(all(row['package']['locations'] == [] for row in saved['findings']))

    def test_missing_and_null_locations_preserve_active_and_suppressed_metadata(self):
        for artifact in [{'name': 'fixture'}, {'name': 'fixture', 'locations': None}, {'name': 'fixture', 'locations': []}]:
            with self.subTest(artifact=artifact):
                match = {'vulnerability': {'id': 'GHSA-fixture', 'severity': 'High'}, 'artifact': artifact}
                suppressed = dict(match, appliedIgnoreRules=[{'reason': 'vendor reports not affected'}])
                report = SCAN.normalize_findings({'matches': [match], 'ignoredMatches': [suppressed]}, IMAGE, REVISION)
                self.assertEqual(report['blockingFindings'], 1)
                self.assertEqual(report['findings'][0]['package']['locations'], [])
                self.assertEqual(report['matcherSuppressedCount'], 1)
                self.assertEqual(report['matcherSuppressed'][0]['package']['locations'], [])
                self.assertEqual(report['matcherSuppressed'][0]['appliedIgnoreRules'][0]['reason'], 'vendor reports not affected')

    def test_malformed_package_locations_fail_closed_for_active_and_suppressed_matches(self):
        artifacts = [None, [], 'package', *({'locations': value} for value in ['', {}, False, 0, ['path'], [None], [{}], [{'path': None}]])]
        for artifact in artifacts:
            for collection in ['matches', 'ignoredMatches']:
                with self.subTest(artifact=artifact, collection=collection):
                    raw = {'matches': [], collection: [{'vulnerability': {'id': 'GHSA-fixture', 'severity': 'High'}, 'artifact': artifact}]}
                    with self.assertRaises(RuntimeError):
                        SCAN.normalize_findings(raw, IMAGE, REVISION)

    def test_identity_failure_diagnostics_contain_booleans_not_raw_configuration(self):
        inspected = dict(INSPECTED, Config={'Env': ['PASSWORD=must-not-export']})
        with self.assertRaises(SCAN.ScanFailure) as failure:
            SCAN.sbom_report(self.sbom(), IMAGE, REVISION, inspected)
        self.assertEqual(failure.exception.code, 'image-identity-check-failed')
        self.assertFalse(failure.exception.diagnostics['identityChecks']['configuration'])
        self.assertTrue(all(isinstance(value, bool) for value in failure.exception.diagnostics['identityChecks'].values()))
        self.assertNotIn('must-not-export', json.dumps(failure.exception.diagnostics))

    def test_classic_daemon_config_digest_binds_despite_api_added_defaults(self):
        sbom = self.sbom()
        sbom['source']['metadata']['userInput'] = CONFIG_DIGEST
        inspected = dict(INSPECTED, Id=CONFIG_DIGEST, Config={'Hostname': '', 'AttachStdin': False})
        inspected.pop('Descriptor')
        report = SCAN.sbom_report(sbom, CONFIG_DIGEST, REVISION, inspected)
        self.assertEqual(report['identityBinding'], 'exact-config-digest')

    def test_classic_raw_config_or_layers_cannot_change_even_with_matching_config_fields(self):
        for change in ('raw-config', 'layers'):
            sbom = self.sbom()
            sbom['source']['metadata']['userInput'] = CONFIG_DIGEST
            inspected = dict(INSPECTED, Id=CONFIG_DIGEST)
            inspected.pop('Descriptor')
            if change == 'raw-config':
                altered = dict(CONFIG, created='2026-01-01T00:00:00Z')
                data = json.dumps(altered).encode()
                sbom['source']['metadata'].update(config=base64.b64encode(data).decode(), imageID='sha256:' + hashlib.sha256(data).hexdigest())
            else:
                inspected['RootFS'] = {'Layers': ['sha256:' + 'e' * 64]}
            with self.assertRaises(SCAN.ScanFailure):
                SCAN.sbom_report(sbom, CONFIG_DIGEST, REVISION, inspected)

    def test_non_config_id_requires_an_explicit_matching_index_descriptor(self):
        for descriptor in [None, {'digest': IMAGE, 'mediaType': 'unknown'},
                           {'digest': CONFIG_DIGEST, 'mediaType': 'application/vnd.oci.image.index.v1+json'}]:
            with self.assertRaises(SCAN.ScanFailure):
                SCAN.sbom_report(self.sbom(), IMAGE, REVISION, dict(INSPECTED, Descriptor=descriptor))

    def test_invalid_or_stale_database_is_rejected(self):
        current = SCAN.datetime.datetime.now(SCAN.datetime.timezone.utc)
        status = {'valid': True, 'built': current.isoformat(), 'schemaVersion': 'v6'}
        SCAN.validate_database(status)
        for change in [{'valid': False}, {'built': (current - SCAN.datetime.timedelta(days=6)).isoformat()},
                       {'built': (current + SCAN.datetime.timedelta(days=1)).isoformat()}, {'schemaVersion': ''}]:
            with self.assertRaises(RuntimeError):
                SCAN.validate_database(dict(status, **change))


if __name__ == '__main__':
    unittest.main()
