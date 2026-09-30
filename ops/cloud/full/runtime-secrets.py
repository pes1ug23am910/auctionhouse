#!/usr/bin/env python3
"""Convert allowlisted runtime secret JSON into mode0400 UID10001 configtree files."""
import json
import os
import sys
from pathlib import Path

allowed = {'spring.datasource.password', 'spring.security.oauth2.client.registration.keycloak.client-id',
           'spring.security.oauth2.client.provider.keycloak.issuer-uri'}
required = allowed
try:
    with Path(sys.argv[1]).open(encoding='utf-8') as stream:
        config = json.load(stream)
    if not isinstance(config, dict) or set(config) - allowed or not required.issubset(config):
        raise ValueError()
    if not config['spring.security.oauth2.client.provider.keycloak.issuer-uri'].startswith('https://'):
        raise ValueError()
    root = Path(sys.argv[2])
    root.mkdir(mode=0o755, parents=True, exist_ok=True)
    root.chmod(0o755)
    for name in allowed - set(config):
        (root / name).unlink(missing_ok=True)
    for name, value in config.items():
        if not isinstance(value, str) or not value:
            raise ValueError()
        target = root / name
        target.write_text(value, encoding='utf-8')
        target.chmod(0o400)
        os.chown(target, 10001, 10001)
except Exception:
    raise SystemExit('Invalid runtime secret configuration; no values logged')
