#!/bin/bash
set -euo pipefail
base=${1:?base URL required}
for attempt in $(seq 1 60); do
  if curl --fail --silent --max-time 5 "$base/actuator/health/readiness" | jq -e '.status == "UP"' >/dev/null; then
    curl --fail --silent --max-time 5 "$base/" | grep -q '<div id="root">'
    curl --fail --silent --max-time 5 "$base/api/auth/csrf" | jq -e '.token and .headerName' >/dev/null
    [[ $(curl --silent --output /dev/null --write-out '%{http_code}' --max-time 5 "$base/api/auth/session") == 401 ]]
    echo 'PASS: readiness, frontend, CSRF and anonymous authorization'
    exit 0
  fi
  sleep 2
done
echo 'Release did not become ready' >&2
exit 1
