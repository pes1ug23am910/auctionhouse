#!/bin/bash
set -euo pipefail
umask 077
export AWS_PAGER=''
[[ $EUID == 0 ]] || { echo 'Use the scoped SSM release document' >&2; exit 1; }
cd /opt/auctionhouse/full
exec 9>/run/auctionhouse-full-release.lock
flock -n 9 || { echo 'Another release is active' >&2; exit 1; }
config=/opt/auctionhouse/full/host-config.json
state=/var/lib/auctionhouse/full/current.json
mode=${1:?Mode required}
role=$(jq -er .role "$config")
if [[ $mode == restore ]]; then
  [[ -f $state ]] || exit 0
  app_digest=$(jq -er .appDigest "$state")
  gateway_digest=$(jq -er .gatewayDigest "$state")
  warehouse_digest=$(jq -er .warehouseDigest "$state")
  cut=00000000-0000-0000-0000-000000000000
  mode=rollback
  [[ $role != dependency ]] || mode=dependencies
else
  app_digest=${2:?App digest required}
  gateway_digest=${3:?Gateway digest required}
  warehouse_digest=${4:?Warehouse digest required}
  cut=${5:?Cut identifier required}
  [[ ${6:?Compatibility required} == schema-compatible ]]
fi
for digest in "$app_digest" "$gateway_digest" "$warehouse_digest"; do [[ $digest =~ ^sha256:[a-f0-9]{64}$ ]]; done
[[ $cut =~ ^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$ ]]
[[ $mode == migrate || $mode == deploy || $mode == rollback || $mode == deactivate || $mode == dependencies || $mode == warehouse ]]
if [[ $mode == deactivate ]]; then
  [[ $role == app ]]
  docker rm -f auctionhouse-gateway auctionhouse-app >/dev/null 2>&1 || true
  [[ ! -f $state ]] || mv "$state" /var/lib/auctionhouse/full/rejected-first-release.json
  exit 0
fi
region=$(jq -er .region "$config")
prefix=$(jq -er .parameterPrefix "$config")
db=$(jq -er .dbHost "$config")
app_repo=$(jq -er .repositories.app "$config")
gateway_repo=$(jq -er .repositories.gateway "$config")
warehouse_repo=$(jq -er .repositories.warehouse "$config")
work=$(mktemp -d /run/auctionhouse-full.XXXXXX)
trap 'rm -rf -- "$work"' EXIT
export DOCKER_CONFIG="$work/docker"
aws ecr get-login-password --region "$region" | docker login --username AWS --password-stdin "${app_repo%%/*}" >/dev/null
if [[ $role == dependency ]]; then
  [[ $mode == dependencies || $mode == warehouse ]]
  docker pull "$warehouse_repo@$warehouse_digest"
  if [[ $mode == warehouse ]]; then
    /opt/auctionhouse/full/warehouse.sh "$warehouse_repo@$warehouse_digest" "$cut" "$work"
    exit 0
  fi
  /opt/auctionhouse/full/dependencies.sh "$work"
else
  [[ $mode == migrate || $mode == deploy || $mode == rollback ]]
  docker pull "$app_repo@$app_digest"
  if [[ $mode == migrate ]]; then
    aws ssm get-parameter --with-decryption --region "$region" --name "$prefix/migration" --query Parameter.Value --output text > "$work/migration-password"
    chown 10001:10001 "$work/migration-password"
    chmod 0400 "$work/migration-password"
    docker run --rm --network auctionhouse --read-only --tmpfs /tmp --cap-drop ALL --security-opt no-new-privileges --memory 512m --cpus 1 \
      -v "$work/migration-password:/run/secrets/password:ro" -v /opt/auctionhouse/full/rds-ca.pem:/app/rds-ca.pem:ro \
      -e "AUCTIONHOUSE_DB_URL=jdbc:postgresql://$db:5432/auctionhouse?sslmode=verify-full&sslrootcert=/app/rds-ca.pem" \
      -e AUCTIONHOUSE_DB_USER=ah_migrator -e AUCTIONHOUSE_RUNTIME_DB_USER=ah_runtime \
      -e AUCTIONHOUSE_DB_PASSWORD_FILE=/run/secrets/password "$app_repo@$app_digest" migrate
    exit 0
  fi
  docker pull "$gateway_repo@$gateway_digest"
  install -d -m 0755 /run/auctionhouse-full-runtime
  prior_config=$(docker inspect auctionhouse-app --format '{{range .Mounts}}{{if eq .Destination "/run/secrets"}}{{.Source}}{{end}}{{end}}' 2>/dev/null || true)
  if [[ -n $prior_config ]]; then
    [[ $prior_config =~ ^/run/auctionhouse-full-runtime/config\.[A-Za-z0-9]+$ && -d $prior_config ]] || { echo 'Unexpected runtime secret mount; refusing replacement' >&2; exit 1; }
  fi
  candidate_config=$(mktemp -d /run/auctionhouse-full-runtime/config.XXXXXX)
  aws ssm get-parameter --with-decryption --region "$region" --name "$prefix/runtime" --query Parameter.Value --output text > "$work/runtime.json"
  python3 runtime-secrets.py "$work/runtime.json" "$candidate_config"
  dependency=$(jq -er .dependencyAddress "$config")
  pool=$(jq -er .runtimeSettings.poolSize "$config")
  log_group=$(jq -er .logGroups.app "$config")
  ordinal=$(jq -er .ordinal "$config")
  origin=$(jq -er .origin "$config")
  run_pair() {
    docker rm -f auctionhouse-gateway auctionhouse-app >/dev/null 2>&1 || true
    docker run -d --name auctionhouse-app --network auctionhouse --restart unless-stopped \
      --read-only --tmpfs /tmp --cap-drop ALL --security-opt no-new-privileges --memory 1g --cpus 1 \
      -p 8080:8080 --log-driver awslogs --log-opt "awslogs-region=$region" --log-opt "awslogs-group=$log_group" --log-opt "awslogs-stream=app-$ordinal" \
      -v "$3:/run/secrets:ro" -v /opt/auctionhouse/full/rds-ca.pem:/app/rds-ca.pem:ro \
      -e SPRING_CONFIG_IMPORT=configtree:/run/secrets/ -e SPRING_PROFILES_ACTIVE=broker -e SPRING_FLYWAY_ENABLED=false \
      -e "AUCTIONHOUSE_DB_URL=jdbc:postgresql://$db:5432/auctionhouse?sslmode=verify-full&sslrootcert=/app/rds-ca.pem" \
      -e AUCTIONHOUSE_DB_USER=ah_runtime -e "AUCTIONHOUSE_DB_POOL_SIZE=$pool" \
      -e 'JAVA_TOOL_OPTIONS=-Xms256m -Xmx512m -XX:+ExitOnOutOfMemoryError -Duser.timezone=UTC' \
      -e AUCTIONHOUSE_CACHE_BACKEND=memcached -e "AUCTIONHOUSE_CACHE_HOST=$dependency" \
      -e "AUCTIONHOUSE_BROKERS=$dependency:9092" -e AUCTIONHOUSE_AUTH_SECURE_COOKIES=true \
      -e MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_INCLUDE=readinessState,db -e SERVER_FORWARD_HEADERS_STRATEGY=native \
      -e SERVER_TOMCAT_REMOTEIP_PROTOCOL_HEADER=x-forwarded-proto -e SERVER_TOMCAT_REMOTEIP_PORT_HEADER=x-forwarded-port \
      -e 'SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES=10\.74\.[0-9]+\.[0-9]+' -e SERVER_SERVLET_SESSION_COOKIE_SECURE=true \
      -e AUCTIONHOUSE_OTEL_ENABLED=true -e "OTEL_EXPORTER_OTLP_ENDPOINT=http://$dependency:4318" \
      -e "OTEL_RESOURCE_ATTRIBUTES=service.instance.id=app-$ordinal,deployment.environment.name=full-experiment" \
      -e SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_KEYCLOAK_CLIENT_AUTHENTICATION_METHOD=none \
      -e SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_KEYCLOAK_AUTHORIZATION_GRANT_TYPE=authorization_code \
      -e SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_KEYCLOAK_SCOPE=openid,profile,email \
      -e "SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_KEYCLOAK_REDIRECT_URI=$origin/login/oauth2/code/keycloak" \
      "$app_repo@$1" >/dev/null || return 1
    docker run -d --name auctionhouse-gateway --network auctionhouse --restart unless-stopped \
      --read-only --tmpfs /tmp --cap-drop ALL --security-opt no-new-privileges --memory 256m --cpus 0.5 \
      -p 3001:3001 -e HOST=0.0.0.0 -e PORT=3001 -e AUCTIONHOUSE_UPSTREAM=http://auctionhouse-app:8080 \
      --log-driver awslogs --log-opt "awslogs-region=$region" --log-opt "awslogs-group=$log_group" --log-opt "awslogs-stream=gateway-$ordinal" \
      "$gateway_repo@$2" >/dev/null || return 1
    /opt/auctionhouse/full/smoke.sh http://127.0.0.1:8080 || return 1
    curl --fail --silent --max-time 5 http://127.0.0.1:3001/health >/dev/null
  }
  if ! run_pair "$app_digest" "$gateway_digest" "$candidate_config"; then
    if [[ -f $state ]]; then
      run_pair "$(jq -er .appDigest "$state")" "$(jq -er .gatewayDigest "$state")" "${prior_config:-$candidate_config}"
      echo 'Previous verified application pair restored'
    else
      docker rm -f auctionhouse-gateway auctionhouse-app >/dev/null 2>&1 || true
    fi
    echo 'Candidate failed local verification; release rejected' >&2
    exit 1
  fi
fi
[[ ! -f $state ]] || cp "$state" /var/lib/auctionhouse/full/previous.json
jq -n --arg appDigest "$app_digest" --arg gatewayDigest "$gateway_digest" --arg warehouseDigest "$warehouse_digest" \
  '{appDigest:$appDigest,gatewayDigest:$gatewayDigest,warehouseDigest:$warehouseDigest}' > "$state.new"
mv "$state.new" "$state"
echo "Verified $role release"
