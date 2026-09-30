#!/usr/bin/env python3
"""Read only named project containers and emit an allowlist, never Docker environment dumps."""
import json
import re
import subprocess
import urllib.request
from pathlib import Path

ALLOWED_ENV = (
    'JAVA_TOOL_OPTIONS', 'AUCTIONHOUSE_DB_POOL_SIZE', 'AUCTIONHOUSE_CACHE_BACKEND',
    'AUCTIONHOUSE_CACHE_HOST', 'AUCTIONHOUSE_BROKERS', 'SPRING_PROFILES_ACTIVE',
    'SPRING_FLYWAY_ENABLED', 'SERVER_FORWARD_HEADERS_STRATEGY',
    'AUCTIONHOUSE_OTEL_ENABLED', 'OTEL_EXPORTER_OTLP_ENDPOINT',
    'AUCTIONHOUSE_AUTH_SECURE_COOKIES', 'HOST', 'PORT', 'AUCTIONHOUSE_UPSTREAM',
    'SERVER_SERVLET_SESSION_COOKIE_SECURE', 'SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_KEYCLOAK_REDIRECT_URI',
)


def request_counts():
    try:
        with urllib.request.urlopen('http://127.0.0.1:9464/metrics', timeout=5) as response:
            exposition = response.read(8 * 1024 * 1024).decode('utf-8')
        counts = {}
        for line in exposition.splitlines():
            match = re.fullmatch(r'http_server_request_duration_seconds_count\{(.*)\}\s+([0-9.eE+-]+)(?:\s+\d+)?', line)
            if not match:
                continue
            labels = {key: json.loads('"' + value + '"') for key, value in re.findall(r'(\w+)="((?:[^"\\]|\\.)*)"', match[1])}
            instance = labels.get('service_instance_id', '')
            if re.fullmatch(r'app-[01]', instance) and labels.get('http_route', '').startswith('/api/auctions'):
                counts[instance] = counts.get(instance, 0) + float(match[2])
        return counts or None
    except Exception:
        return None


def inspect(name):
    result = subprocess.run(['docker', 'inspect', name], capture_output=True, text=True, timeout=10)
    if result.returncode:
        return {'name': name, 'present': False}
    data = json.loads(result.stdout)[0]
    environment = dict(item.split('=', 1) for item in data['Config'].get('Env', []) if '=' in item)
    return {
        'name': name, 'present': True, 'running': data['State']['Running'],
        'health': data['State'].get('Health', {}).get('Status', 'not-configured'),
        'imageReference': data['Config']['Image'], 'imageId': data['Image'],
        'memoryLimitBytes': data['HostConfig']['Memory'], 'nanoCpus': data['HostConfig']['NanoCpus'],
        'readOnlyRootFilesystem': data['HostConfig']['ReadonlyRootfs'],
        'runtimeEnvironment': {key: environment[key] for key in ALLOWED_ENV if key in environment},
        'demoCredentialsAbsent': not environment.get('AUCTIONHOUSE_DEMO_PASSWORD_HASH') and not environment.get('AUCTIONHOUSE_AUTH_DEMO_PASSWORD_HASH'),
    }


def main():
    config = json.loads(Path('/opt/auctionhouse/full/host-config.json').read_text())
    names = ['auctionhouse-app', 'auctionhouse-gateway'] if config['role'] == 'app' else [
        'redpanda', 'memcached', 'collector', 'tempo', 'prometheus', 'grafana']
    readiness = False
    if config['role'] == 'app':
        try:
            with urllib.request.urlopen('http://127.0.0.1:8080/actuator/health/readiness', timeout=5) as response:
                readiness = response.status == 200 and json.load(response).get('status') == 'UP'
        except Exception:
            pass
    record = Path('/var/lib/auctionhouse/full/current.json')
    release = json.loads(record.read_text()) if record.exists() else None
    print(json.dumps({
        'schemaVersion': 1, 'role': config['role'], 'sessionId': config['sessionId'],
        'configuredRuntimeSettings': config['runtimeSettings'],
        'configuredRuntimeSha256': config['runtimeConfigurationSha256'],
        'readiness': readiness, 'release': release,
        'httpRequestsByInstance': request_counts() if config['role'] == 'dependency' else None,
        'containers': [inspect(name) for name in names],
    }, separators=(',', ':')))


if __name__ == '__main__':
    main()
