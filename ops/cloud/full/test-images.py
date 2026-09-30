#!/usr/bin/env python3
"""Non-root/read-only gateway and warehouse image checks using a labelled synthetic cut."""
import argparse
import importlib.util
import json
import re
import subprocess
import time
import urllib.request
from pathlib import Path
from uuid import uuid4


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--gateway-image', required=True)
    parser.add_argument('--warehouse-image', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if not all(re.fullmatch(r'(?:[a-zA-Z0-9./:_-]+@)?sha256:[a-f0-9]{64}', image) for image in [args.gateway_image, args.warehouse_image]):
        raise SystemExit('Use immutable local IDs or registry digests')
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    root = Path(__file__).resolve().parents[3]
    fixture = load(root / 'warehouse/tests/fixtures.py', 'image_fixture')
    validation = load(root / 'warehouse/src/auctionwarehouse/validation.py', 'image_validation')
    events = fixture.all_events()
    for event in events:
        validation.validate_event(event)
    manifest = validation.make_manifest([event['eventId'] for event in events], str(uuid4()), 'synthetic-image-packaging-fixture')
    inputs = output / 'input'
    inputs.mkdir()
    (inputs / 'manifest.json').write_text(json.dumps(manifest), encoding='utf-8')
    (inputs / 'events.jsonl').write_text(''.join(validation.canonical(event) + '\n' for event in events), encoding='utf-8')
    for path in [inputs, *inputs.iterdir()]:
        path.chmod(0o755 if path.is_dir() else 0o644)
    suffix = uuid4().hex[:12]
    gateway = 'auctionhouse-gateway-smoke-' + suffix
    warehouse = 'auctionhouse-warehouse-smoke-' + suffix
    volume = 'auctionhouse-warehouse-smoke-' + suffix

    def docker(*parts, check=True):
        result = subprocess.run(['docker', *parts], capture_output=True, text=True, timeout=900)
        if check and result.returncode:
            raise RuntimeError('Docker smoke command failed: ' + ' '.join(parts[:2]) + '; inspect retained local log')
        return result

    try:
        metadata = {}
        for kind, image in [('gateway', args.gateway_image), ('warehouse', args.warehouse_image)]:
            data = json.loads(docker('image', 'inspect', image).stdout)[0]
            if data['Config']['User'] != '10001:10001':
                raise RuntimeError('Packaged images must explicitly run as UID10001')
            metadata[kind] = {'id': data['Id'], 'user': data['Config']['User']}
        docker('run', '-d', '--name', gateway, '--label', 'Project=auctionhouse', '--label', 'Purpose=full-image-smoke',
               '--read-only', '--tmpfs', '/tmp:size=32m', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges',
               '--memory', '256m', '--cpus', '0.5', '-p', '127.0.0.1::3001', args.gateway_image)
        port = docker('port', gateway, '3001/tcp').stdout.strip().split(':')[-1]
        health = None
        for _ in range(30):
            try:
                with urllib.request.urlopen('http://127.0.0.1:' + port + '/health', timeout=2) as response:
                    health = json.load(response)
                break
            except Exception:
                time.sleep(0.2)
        if health is None:
            raise RuntimeError('Packaged gateway listener did not become ready')
        docker('volume', 'create', '--label', 'Project=auctionhouse', '--label', 'Purpose=full-image-smoke', volume)
        docker('run', '--rm', '--user', '0:0', '--entrypoint', 'sh', '-v', volume + ':/data', args.warehouse_image,
               '-c', 'chown 10001:10001 /data && chmod 0755 /data')
        result = docker('run', '--name', warehouse, '--label', 'Project=auctionhouse', '--label', 'Purpose=full-image-smoke',
                        '--read-only', '--tmpfs', '/tmp:size=256m', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges',
                        '--memory', '1536m', '--cpus', '1', '-v', volume + ':/data', '-v', str(inputs) + ':/input:ro',
                        '--entrypoint', 'python', args.warehouse_image, '/warehouse/tools/run_cut.py',
                        '--manifest', '/input/manifest.json', '--input', '/input/events.jsonl', '--output', '/data/results', '--runs', '3', check=False)
        (output / 'warehouse-container.log').write_text(result.stdout + result.stderr, encoding='utf-8')
        docker('cp', warehouse + ':/data/results', str(output / 'warehouse'), check=False)
        if result.returncode:
            raise RuntimeError('Warehouse container failed; retained warehouse-container.log and partial output')
        summary = json.loads((output / 'warehouse/summary.json').read_text())
        if [run['factCount'] for run in summary['runs']] != [len(events)] * 3 or [run['loadAttempts'] for run in summary['runs']] != [len(events), len(events) * 2, len(events) * 3]:
            raise RuntimeError('Container replay identity/count oracle failed')
        report = {'scope': 'synthetic image packaging; not real broker/RDS/cloud evidence', 'images': metadata,
                  'gatewayHealth': health, 'warehouseEventCount': len(events), 'warehouseReplayCount': 3,
                  'warehouseFacts': [run['factCount'] for run in summary['runs']],
                  'warehouseAttempts': [run['loadAttempts'] for run in summary['runs']],
                  'passed': True}
        (output / 'summary.json').write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps(report))
    finally:
        for name in [gateway, warehouse]:
            docker('rm', '-f', name, check=False)
        docker('volume', 'rm', volume, check=False)


if __name__ == '__main__':
    main()
