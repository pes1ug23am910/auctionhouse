#!/usr/bin/env python3
"""Invoke the thin release document at an explicitly reviewed numeric version."""
import argparse
import json
from pathlib import Path
import re
import subprocess


def validate_version(version):
    if not isinstance(version, str) or not re.fullmatch(r'[1-9][0-9]*', version):
        raise ValueError('An explicitly reviewed positive numeric document version is required')
    return version


def command_arguments(document, version, instance, digest, region):
    validate_version(version)
    checks = [(document, r'auctionhouse-[a-z0-9-]+-release'),
              (instance, r'i-(?:[0-9a-f]{8}|[0-9a-f]{17})'),
              (digest, r'sha256:[0-9a-f]{64}'),
              (region, r'[a-z]{2}(?:-[a-z]+)+-[1-9][0-9]*')]
    if any(not isinstance(value, str) or not re.fullmatch(pattern, value) for value, pattern in checks):
        raise ValueError('The exact project document, instance, image digest and Region are required')
    parameters = {'Mode': ['deploy'], 'Digest': [digest], 'Compatibility': ['schema-compatible']}
    return ['aws', '--region', region, '--output', 'json', '--no-cli-pager', 'ssm', 'send-command',
            '--document-name', document, '--document-version', version, '--instance-ids', instance,
            '--parameters', json.dumps(parameters)]


def send(document, version, instance, digest, region, output):
    command = command_arguments(document, version, instance, digest, region)
    if output.exists() or not output.parent.is_dir():
        raise ValueError('Command evidence must be a new file in an existing directory')
    result = subprocess.run(command, stdin=subprocess.DEVNULL, capture_output=True,
                            text=True, encoding='utf-8', timeout=120)
    if result.returncode:
        raise RuntimeError('Scoped SSM command failed; no raw command output is exported')
    actual = json.loads(result.stdout)['Command']
    command_id = actual.get('CommandId', '')
    if (actual.get('DocumentName') != document or actual.get('DocumentVersion') != version
            or not re.fullmatch(r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}', command_id)):
        raise RuntimeError('SSM response does not identify the requested document version')
    output.write_text(json.dumps({'commandId': command_id, 'documentName': document,
                                  'documentVersion': version, 'instanceId': instance,
                                  'imageDigest': digest, 'region': region}, indent=2) + '\n', encoding='utf-8')
    return command_id


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--document-version', required=True)
    parser.add_argument('--validate-only', action='store_true')
    parser.add_argument('--document')
    parser.add_argument('--instance')
    parser.add_argument('--digest')
    parser.add_argument('--region')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    validate_version(args.document_version)
    if args.validate_only:
        return
    if not args.output:
        parser.error('--output is required when sending a command')
    print(send(args.document, args.document_version, args.instance, args.digest, args.region, args.output))


if __name__ == '__main__':
    main()
