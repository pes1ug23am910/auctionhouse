#!/usr/bin/env python3
"""Scan exact local release images; retain package facts without container configuration."""
import argparse
import base64
import collections
import datetime
import hashlib
import http.client
import json
import os
from pathlib import Path
import platform
import re
import shutil
import socket
import subprocess
import tarfile
import tempfile
import urllib.request
import zipfile

TOOLS = {
    'grype': {'version': '0.119.0', 'windows': '1db5c23b8ba0038a04acebed9c17945e1ade68d9f83e2fe1c101e4fb1feb9a48',
              'linux': '3fa2dc4b924621ab65404cf08d0b8438d896d80ab949c9d5a4ca283c36004c9b'},
    'syft': {'version': '1.52.0', 'windows': 'de787a374cf961c56fd7b206b2e183295abba32d0af3cb44d9aef2357ca9eda2',
             'linux': 'caeedb81fb0491615f1ebd1761e4145d41ee86dd2cc7bf80669f9f5ad9d6133d'},
}
DIGEST = re.compile(r'sha256:[0-9a-f]{64}\Z')
AGENT_VERSION = '2.31.1'
AGENT_JAR_SHA256 = 'bbf83c151b6400709e2f225bdd07a04f839d9d13b8b93464241333fd25d3e3ba'
AGENT_SBOM_SHA256 = '432b9ad8dd2a420c3770d2d2dc9b5e10bbcfae2200bef4a18a9793ffe0b0bf67'


class ScanFailure(RuntimeError):
    def __init__(self, code, diagnostics=None):
        super().__init__(code)
        self.code = code
        self.diagnostics = diagnostics or {}


def sha256(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def save(path, value):
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + '\n', encoding='utf-8')


def install(name, cache):
    system = platform.system().lower()
    if system not in ('windows', 'linux') or platform.machine().lower() not in ('amd64', 'x86_64'):
        raise RuntimeError('The pinned scanner tools support Windows/Linux AMD64 only')
    tool = TOOLS[name]
    suffix = 'zip' if system == 'windows' else 'tar.gz'
    filename = f'{name}_{tool["version"]}_{system}_amd64.{suffix}'
    archive = cache / filename
    if not archive.is_file():
        url = f'https://github.com/anchore/{name}/releases/download/v{tool["version"]}/{filename}'
        temporary = archive.with_suffix(archive.suffix + '.partial')
        with urllib.request.urlopen(url, timeout=180) as response, temporary.open('wb') as output:
            while block := response.read(1024 * 1024):
                output.write(block)
        temporary.replace(archive)
    if sha256(archive) != tool[system]:
        raise RuntimeError(f'{name} official archive checksum mismatch')
    executable = name + ('.exe' if system == 'windows' else '')
    if system == 'windows':
        with zipfile.ZipFile(archive) as package:
            content = package.read(executable)
    else:
        with tarfile.open(archive, 'r:gz') as package:
            member = package.getmember(executable)
            if not member.isfile():
                raise RuntimeError('Tool archive executable is not a regular file')
            with package.extractfile(member) as stream:
                content = stream.read()
    # Extract one named member only, and replace any stale local binary.
    binary = cache / executable
    binary.write_bytes(content)
    binary.chmod(0o700)
    return binary, {'version': tool['version'], 'archiveSha256': tool[system], 'binarySha256': sha256(binary)}


def clean_environment(cache):
    env = {key: value for key, value in os.environ.items() if not key.upper().startswith(('GRYPE_', 'SYFT_'))}
    env.update(GRYPE_CHECK_FOR_APP_UPDATE='false', SYFT_CHECK_FOR_APP_UPDATE='false',
               GRYPE_DB_CACHE_DIR=str(cache / 'db'), GRYPE_DB_AUTO_UPDATE='false',
               GRYPE_DB_VALIDATE_BY_HASH_ON_START='true', GRYPE_DB_VALIDATE_AGE='true',
               GRYPE_DB_MAX_ALLOWED_BUILT_AGE='120h', GRYPE_EXTERNAL_SOURCES_ENABLE='false')
    return env


def execute(command, env, cwd, timeout=900):
    result = subprocess.run([str(item) for item in command], env=env, cwd=cwd,
                            stdin=subprocess.DEVNULL, capture_output=True, text=True,
                            encoding='utf-8', errors='replace', timeout=timeout)
    if result.returncode:
        # Scanner stderr may contain local paths/configuration; keep failure output bounded.
        tool = Path(str(command[0])).stem
        raise ScanFailure('scanner-command-failed', {'tool': tool if tool in ('syft', 'grype', 'docker') else 'other',
                                                    'exitCode': result.returncode})
    return result.stdout


def inspect_image(image_id, env, work):
    if shutil.which('docker'):
        return json.loads(execute(['docker', 'image', 'inspect', image_id], env, work))[0]
    # A bounded Linux scanner container needs only the local Engine's read endpoint.
    if os.name != 'posix':
        raise RuntimeError('Docker image inspection requires the CLI or a local Unix socket')
    connection = http.client.HTTPConnection('localhost', timeout=30)
    connection.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    connection.sock.settimeout(30)
    try:
        connection.sock.connect('/var/run/docker.sock')
        connection.request('GET', '/images/' + image_id + '/json')
        response = connection.getresponse()
        if response.status != 200:
            raise RuntimeError('Local Docker Engine image inspection failed')
        return json.load(response)
    finally:
        connection.close()


def package_facts(artifact):
    return {key: artifact[key] for key in ('id', 'name', 'version', 'type', 'purl', 'foundBy', 'language') if key in artifact} | {
        'locations': sorted({loc['path'] for loc in artifact.get('locations', []) if isinstance(loc.get('path'), str)})}


def sbom_report(sbom, image_id, revision, inspected):
    metadata = sbom.get('source', {}).get('metadata', {})
    config_bytes = base64.b64decode(metadata.get('config', ''), validate=True)
    config_digest = 'sha256:' + hashlib.sha256(config_bytes).hexdigest()
    config = json.loads(config_bytes)
    layers = config.get('rootfs', {}).get('diff_ids', [])
    checks = {'inspectedId': inspected.get('Id') == image_id, 'requestedId': metadata.get('userInput') == image_id,
              'configDigest': config_digest == metadata.get('imageID'), 'nonemptyLayers': bool(layers),
              'orderedLayers': layers == inspected.get('RootFS', {}).get('Layers'),
              'layerDigests': all(DIGEST.fullmatch(layer) for layer in layers),
              'linuxAmd64': config.get('os') == 'linux' and config.get('architecture') == 'amd64',
              'inspectedPlatform': config.get('os') == inspected.get('Os') and config.get('architecture') == inspected.get('Architecture'),
              'configuration': config.get('config') == inspected.get('Config'),
              'daemonIdIsConfigDigest': inspected.get('Id') == config_digest}
    required = [value for key, value in checks.items() if key != 'daemonIdIsConfigDigest']
    if not all(required):
        raise ScanFailure('image-identity-check-failed', {'identityChecks': checks})
    packages = [package_facts(item) for item in sbom.get('artifacts', [])]
    if not packages or not any(item.get('type') == 'deb' for item in packages):
        raise RuntimeError('Release SBOM contains no expected OS package inventory')
    distro = sbom.get('distro', {})
    if distro.get('id') not in ('debian', 'ubuntu') or not distro.get('versionID'):
        raise RuntimeError('Release SBOM has no recognized versioned operating system')
    return {'schemaVersion': 1, 'format': 'auctionhouse-package-inventory', 'imageId': image_id,
            'configDigest': config_digest, 'rootFsLayers': layers, 'platform': 'linux/amd64',
            'sourceRevision': revision, 'generator': {'name': 'syft', 'version': TOOLS['syft']['version']},
            'distro': {key: sbom.get('distro', {}).get(key) for key in ('id', 'name', 'versionID')},
            'packages': sorted(packages, key=lambda row: (row.get('type', ''), row.get('name', ''), row.get('version', '')))}


def coverage_report(name, inventory):
    packages = inventory['packages']
    def present(package_type, package_name):
        return any(row.get('type') == package_type and row.get('name') == package_name for row in packages)
    if name == 'app':
        checks = {'springBoot': present('java-archive', 'spring-boot'),
                  'openTelemetryAgent': any(row.get('type') == 'java-archive' and any('/opt/otel/opentelemetry-javaagent.jar' in path for path in row['locations']) for row in packages)}
    elif name == 'gateway':
        checks = {'gatewayPackage': present('npm', 'auctionhouse-sse-gateway'),
                  'nodeRuntime': present('binary', 'node')}
    else:
        checks = {'duckdb': present('python', 'duckdb'), 'dbtCore': present('python', 'dbt-core')}
    if not all(checks.values()):
        raise RuntimeError('The package catalog does not cover the expected release runtime')
    return checks


def vulnerability_report(report, image_id, revision, config_digest=None):
    source = report.get('source', {})
    target = source.get('target', {})
    if source.get('type') != 'image' or target.get('userInput') != image_id or target.get('imageID') != (config_digest or image_id):
        raise RuntimeError('Vulnerability report image identity differs from the SBOM')
    return normalize_findings(report, image_id, revision)


def normalize_findings(report, image_id, revision):
    if not isinstance(report.get('matches'), list):
        raise RuntimeError('Scanner report has no complete findings array')
    def finding(match):
        vuln = match['vulnerability']
        detail = {key: vuln[key] for key in ('id', 'namespace', 'dataSource', 'fix', 'urls') if key in vuln}
        detail['severity'] = vuln.get('severity') if vuln.get('severity') in ('Negligible', 'Low', 'Medium', 'High', 'Critical') else 'Unknown'
        return {'vulnerability': detail, 'package': package_facts(match['artifact']),
                'matchTypes': sorted({item.get('type', '') for item in match.get('matchDetails', [])}),
                'relatedVulnerabilities': [{key: related[key] for key in ('id', 'severity', 'namespace', 'dataSource', 'urls') if key in related}
                                           for related in match.get('relatedVulnerabilities', [])]}
    findings = [finding(match) for match in report['matches']]
    suppressed = []
    for match in report.get('ignoredMatches', []):
        item = finding(match)
        item['appliedIgnoreRules'] = [{key: rule[key] for key in ('vulnerability', 'reason', 'namespace', 'fix-state',
                                      'package', 'vex-status', 'vex-justification', 'match-type') if key in rule}
                                     for rule in match.get('appliedIgnoreRules', [])]
        suppressed.append(item)
    counts = collections.Counter(row['vulnerability']['severity'] for row in findings)
    blocked = sum(counts[key] for key in ('High', 'Critical', 'Unknown'))
    return {'schemaVersion': 1, 'imageId': image_id, 'sourceRevision': revision,
            'findings': findings, 'severityCounts': dict(counts), 'blockingFindings': blocked,
            'matcherSuppressed': suppressed, 'matcherSuppressedCount': len(report.get('ignoredMatches', [])),
            'policy': 'Block High, Critical and Unknown severity; preserve all findings; no project exceptions.'}


def verify_agent_jar(sbom):
    entries = [item for item in sbom.get('files', []) if item.get('location', {}).get('path') == '/opt/otel/opentelemetry-javaagent.jar']
    if len(entries) != 1 or not any(digest.get('algorithm') == 'sha256' and digest.get('value') == AGENT_JAR_SHA256 for digest in entries[0].get('digests', [])):
        raise RuntimeError('Embedded agent does not match the reviewed official SBOM release')


def scan_agent(sbom, grype, config, cache, work, env, output, image_id, revision):
    verify_agent_jar(sbom)
    archive = cache / f'otel-{AGENT_VERSION}-SBOM.zip'
    if not archive.exists():
        url = f'https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v{AGENT_VERSION}/opentelemetry-java-instrumentation-SBOM.zip'
        with urllib.request.urlopen(url, timeout=120) as response:
            archive.write_bytes(response.read())
    if sha256(archive) != AGENT_SBOM_SHA256:
        raise RuntimeError('Official agent SBOM archive checksum mismatch')
    with zipfile.ZipFile(archive) as package:
        data = package.read('sboms/opentelemetry-javaagent.spdx.json')
    declared = json.loads(data)
    if len(declared.get('packages', [])) != 93 or not any(row.get('name') == 'javaagent' and row.get('versionInfo') == AGENT_VERSION for row in declared['packages']):
        raise RuntimeError('Official agent SBOM component/version contract changed')
    path = output / 'app.agent.spdx.json'
    path.write_bytes(data)
    raw = json.loads(execute([grype, '-c', config, 'sbom:' + str(path), '-o', 'json'], env, work))
    if raw.get('source') != {'type': 'sbom-file', 'target': str(path)}:
        raise RuntimeError('Agent matching report does not identify the verified official SBOM')
    findings = normalize_findings(raw, image_id, revision)
    findings['scope'] = 'Official declared agent components; supplementary to discovered image packages'
    findings['agentVersion'] = AGENT_VERSION
    findings['agentJarSha256'] = AGENT_JAR_SHA256
    findings['officialSbomArchiveSha256'] = AGENT_SBOM_SHA256
    save(output / 'app.agent.vulnerabilities.json', findings)
    return {'scope': findings['scope'], 'componentCount': len(declared['packages']), 'agentJarSha256': AGENT_JAR_SHA256,
            'officialSbomArchiveSha256': AGENT_SBOM_SHA256, 'officialSbomSha256': sha256(path),
            'findingsSha256': sha256(output / 'app.agent.vulnerabilities.json'),
            'severityCounts': findings['severityCounts'], 'blockingFindings': findings['blockingFindings']}


def cyclonedx_report(inventory):
    """Represent the sanitized Syft component inventory in standard CycloneDX 1.6."""
    components = []
    for package in inventory['packages']:
        component = {'type': 'library', 'bom-ref': package['id'], 'name': package['name'], 'version': package['version']}
        if package.get('purl'):
            component['purl'] = package['purl']
        components.append(component)
    return {'bomFormat': 'CycloneDX', 'specVersion': '1.6', 'version': 1,
            'metadata': {'component': {'type': 'container', 'name': 'auctionhouse-release', 'version': inventory['imageId']},
                         'properties': [{'name': 'auctionhouse:sourceRevision', 'value': inventory['sourceRevision']},
                                        {'name': 'auctionhouse:inventoryGenerator', 'value': 'syft/' + TOOLS['syft']['version']}]},
            'components': components}


def parse_images(values):
    images = {}
    for value in values:
        name, separator, image_id = value.partition('=')
        if not separator or name not in ('app', 'gateway', 'warehouse') or name in images or not DIGEST.fullmatch(image_id):
            raise ValueError('Each image must be a unique app/gateway/warehouse=sha256:64hex local image ID')
        images[name] = image_id
    return images


def validate_database(status):
    if status.get('valid') is not True or not status.get('built') or not status.get('schemaVersion'):
        raise RuntimeError('The vulnerability database is not valid and versioned')
    built = datetime.datetime.fromisoformat(status['built'].replace('Z', '+00:00'))
    age = datetime.datetime.now(datetime.timezone.utc) - built
    if built.tzinfo is None or age < datetime.timedelta(0) or age > datetime.timedelta(hours=120):
        raise RuntimeError('The vulnerability database timestamp is outside the allowed freshness window')


def validate_release_image(inspected, image_id, revision, baseline):
    if inspected.get('Id') != image_id or inspected.get('Os') != 'linux' or inspected.get('Architecture') != 'amd64':
        raise RuntimeError('Expected the exact Linux AMD64 image in the local daemon')
    if not baseline and (inspected.get('Config') or {}).get('Labels', {}).get('org.opencontainers.image.revision') != revision:
        raise RuntimeError('Image source revision does not match the release')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', action='append', required=True)
    parser.add_argument('--revision', required=True)
    parser.add_argument('--cache', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--baseline', action='store_true', help='Label an older local artifact; cannot establish current release provenance')
    args = parser.parse_args()
    if platform.system() != 'Linux' or platform.machine().lower() not in ('amd64', 'x86_64'):
        raise SystemExit('Release image scanning requires Linux AMD64; native Windows Syft layer extraction is unsupported')
    images = parse_images(args.image)
    if not re.fullmatch('[0-9a-f]{40}', args.revision):
        raise SystemExit('A full source commit SHA is required')
    args.output = args.output.resolve()
    args.cache = args.cache.resolve()
    args.output.mkdir(parents=True, exist_ok=False)
    args.cache.mkdir(parents=True, exist_ok=True)
    summary = {'schemaVersion': 1, 'sourceRevision': args.revision,
               'scope': 'prior local artifacts' if args.baseline else 'current release artifacts',
               'observedAtUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'images': {}, 'passed': False}
    stage = 'tool installation'
    try:
        env = clean_environment(args.cache)
        with tempfile.TemporaryDirectory(prefix='auctionhouse-image-scan-') as temporary:
            work = Path(temporary)
            grype, grype_metadata = install('grype', args.cache)
            syft, syft_metadata = install('syft', args.cache)
            summary['tools'] = {'grype': grype_metadata, 'syft': syft_metadata}
            grype_config = work / 'grype.yaml'
            grype_config.write_text('ignore: []\nexclude: []\ninclude-matcher-suppressions: true\nexternal-sources:\n  enable: false\n', encoding='utf-8')
            syft_config = work / 'syft.yaml'
            syft_config.write_text('check-for-app-update: false\nenrich: []\njava:\n  use-network: false\n', encoding='utf-8')
            stage = 'vulnerability database update and validation'
            execute([grype, '-c', grype_config, 'db', 'update'], env, work)
            status = json.loads(execute([grype, '-c', grype_config, 'db', 'status', '-o', 'json'], env, work))
            validate_database(status)
            summary['database'] = {key: value for key, value in status.items() if key not in ('location', 'path')}
            for name, image_id in images.items():
                stage = name + ' image inspection'
                inspected = inspect_image(image_id, env, work)
                validate_release_image(inspected, image_id, args.revision, args.baseline)
                raw_sbom = work / f'{name}.json'
                stage = name + ' Syft scan'
                execute([syft, '-c', syft_config, 'scan', 'docker:' + image_id, '--parallelism', '2',
                         '-o', 'syft-json=' + str(raw_sbom)], env, work)
                stage = name + ' SBOM identity and catalog validation'
                raw_inventory = json.loads(raw_sbom.read_text(encoding='utf-8'))
                inventory = sbom_report(raw_inventory, image_id, args.revision, inspected)
                save(args.output / f'{name}.sbom.json', inventory)
                save(args.output / f'{name}.cdx.json', cyclonedx_report(inventory))
                coverage = coverage_report(name, inventory)
                stage = name + ' vulnerability matching'
                report = json.loads(execute([grype, '-c', grype_config, 'sbom:' + str(raw_sbom), '-o', 'json'], env, work))
                findings = vulnerability_report(report, image_id, args.revision, inventory['configDigest'])
                save(args.output / f'{name}.vulnerabilities.json', findings)
                summary['images'][name] = {'imageId': image_id, 'packageCount': len(inventory['packages']),
                                          'configDigest': inventory['configDigest'], 'rootFsLayers': inventory['rootFsLayers'],
                                          'catalogCoverage': coverage,
                                          'severityCounts': findings['severityCounts'], 'blockingFindings': findings['blockingFindings'],
                                          'sbomSha256': sha256(args.output / f'{name}.sbom.json'),
                                          'cycloneDxSha256': sha256(args.output / f'{name}.cdx.json'),
                                          'findingsSha256': sha256(args.output / f'{name}.vulnerabilities.json')}
                if name == 'app':
                    stage = 'official declared agent component matching'
                    supplement = scan_agent(raw_inventory, grype, grype_config, args.cache, work, env, args.output, image_id, args.revision)
                    summary['images'][name]['agentSupplement'] = supplement
                    summary['images'][name]['blockingFindings'] += supplement['blockingFindings']
                print(json.dumps({'image': name, **summary['images'][name]}), flush=True)
            summary['passed'] = not any(row['blockingFindings'] for row in summary['images'].values())
    except Exception as error:
        summary['errorType'] = type(error).__name__
        summary['failedStage'] = stage
        if isinstance(error, ScanFailure):
            summary['failureCode'] = error.code
            summary['diagnostics'] = error.diagnostics
        raise SystemExit(f'Image scan failed during {stage} ({type(error).__name__}); release is blocked') from None
    finally:
        save(args.output / 'summary.json', summary)
    if not summary['passed']:
        raise SystemExit('Image vulnerability policy failed; inspect the preserved findings before release')


if __name__ == '__main__':
    main()
