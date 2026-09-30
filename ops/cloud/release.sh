#!/bin/bash
# Installed by reviewed Terraform user data. Not fetched from a mutable branch.
set -euo pipefail
umask 077
[[ $EUID == 0 ]] || { echo 'Run through the scoped SSM document' >&2; exit 1; }
source /etc/auctionhouse/deployment.env
mode=${1:?deploy or rollback required}
digest=${2:?sha256 digest required}
compatibility=${3:?schema-compatible attestation required}
[[ $mode == deploy || $mode == rollback ]]
[[ $digest =~ ^sha256:[a-f0-9]{64}$ ]]
[[ $compatibility == schema-compatible ]]
exec 9>/run/auctionhouse-release.lock
flock -n 9 || { echo 'Another release is in progress' >&2; exit 1; }
image="$REPOSITORY@$digest"
previous=''
[[ ! -f /var/lib/auctionhouse/current ]] || previous=$(cat /var/lib/auctionhouse/current)
# Pull before touching the running application. Docker credentials stay in tmpfs.
work=$(mktemp -d /run/auctionhouse-release.XXXXXX)
trap 'rm -rf -- "$work"' EXIT
export DOCKER_CONFIG="$work/docker"
aws ecr get-login-password --region "$AWS_REGION" | docker login --username AWS --password-stdin "${REPOSITORY%%/*}" >/dev/null
docker pull "$image"
aws ssm get-parameter --with-decryption --name "$PARAMETER_PREFIX/runtime" --region "$AWS_REGION" --query Parameter.Value --output text > "$work/runtime.json"
# Convert only an explicit allowlist into a configtree; values never appear on a process command line.
install -d -m 0755 /run/auctionhouse-runtime
python3 - "$work/runtime.json" /run/auctionhouse-runtime <<'PY'
import json, pathlib, sys
config=json.load(open(sys.argv[1]))
allowed={'spring.datasource.password','spring.security.oauth2.client.registration.keycloak.client-id',
         'spring.security.oauth2.client.registration.keycloak.client-secret',
         'spring.security.oauth2.client.provider.keycloak.issuer-uri'}
if not isinstance(config,dict) or set(config)-allowed or 'spring.datasource.password' not in config:
    raise SystemExit('Invalid runtime secret configuration')
for key in allowed - set(config):
    (pathlib.Path(sys.argv[2])/key).unlink(missing_ok=True)
for key,value in config.items():
    if not isinstance(value,str) or not value: raise SystemExit('Empty or non-string runtime secret')
    p=pathlib.Path(sys.argv[2])/key
    p.write_text(value)
    p.chmod(0o400)
    p.chown(10001,10001)
PY
if [[ $mode == deploy ]]; then
  aws ssm get-parameter --with-decryption --name "$PARAMETER_PREFIX/migration" --region "$AWS_REGION" --query Parameter.Value --output text > "$work/migration-password"
  chown 10001:10001 "$work/migration-password"
  chmod 0400 "$work/migration-password"
  docker run --rm --network auctionhouse --read-only --tmpfs /tmp --cap-drop ALL --security-opt no-new-privileges \
    --memory 512m --cpus 1 -v "$work/migration-password:/run/secrets/password:ro" \
    -e "AUCTIONHOUSE_DB_URL=jdbc:postgresql://$DB_HOST:5432/auctionhouse?sslmode=verify-full&sslrootcert=/app/rds-ca.pem" \
    -v /etc/auctionhouse/rds-ca.pem:/app/rds-ca.pem:ro \
    -e AUCTIONHOUSE_DB_USER=ah_migrator -e AUCTIONHOUSE_RUNTIME_DB_USER=ah_runtime \
    -e AUCTIONHOUSE_DB_PASSWORD_FILE=/run/secrets/password "$image" migrate
fi
run_app() {
  docker rm -f auctionhouse-app >/dev/null 2>&1 || true
  docker run -d --name auctionhouse-app --network auctionhouse --restart unless-stopped \
    --read-only --tmpfs /tmp --cap-drop ALL --security-opt no-new-privileges --memory 768m --cpus 1 \
    -p 127.0.0.1:8080:8080 \
    --log-driver awslogs --log-opt "awslogs-region=$AWS_REGION" --log-opt "awslogs-group=$LOG_GROUP" --log-opt awslogs-stream=app \
    -v /run/auctionhouse-runtime:/run/secrets:ro -v /etc/auctionhouse/rds-ca.pem:/app/rds-ca.pem:ro \
    -e SPRING_CONFIG_IMPORT=configtree:/run/secrets/ -e SPRING_FLYWAY_ENABLED=false \
    -e "AUCTIONHOUSE_DB_URL=jdbc:postgresql://$DB_HOST:5432/auctionhouse?sslmode=verify-full&sslrootcert=/app/rds-ca.pem" \
    -e AUCTIONHOUSE_DB_USER=ah_runtime -e AUCTIONHOUSE_DB_POOL_SIZE=8 \
    -e MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_INCLUDE=readinessState,db \
    -e SERVER_FORWARD_HEADERS_STRATEGY=framework \
    -e SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_KEYCLOAK_CLIENT_AUTHENTICATION_METHOD=none \
    -e SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_KEYCLOAK_AUTHORIZATION_GRANT_TYPE=authorization_code \
    -e SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_KEYCLOAK_SCOPE=openid,profile,email \
    "$1" >/dev/null
}
if run_app "$image" && /opt/auctionhouse/smoke.sh http://127.0.0.1:8080 && /opt/auctionhouse/smoke.sh "https://$DOMAIN"; then
  [[ -z $previous || $previous == "$image" ]] || printf '%s\n' "$previous" > /var/lib/auctionhouse/previous
  printf '%s\n' "$image" > /var/lib/auctionhouse/current.new
  mv /var/lib/auctionhouse/current.new /var/lib/auctionhouse/current
  echo "Released $image"
else
  if [[ -n $previous ]]; then
    run_app "$previous"
    /opt/auctionhouse/smoke.sh "https://$DOMAIN"
    echo "Rollback verified: $previous"
  else
    # A rejected first release must not remain reachable or restart itself.
    docker rm -f auctionhouse-app >/dev/null 2>&1 || true
  fi
  echo 'Candidate failed smoke verification' >&2
  exit 1
fi
