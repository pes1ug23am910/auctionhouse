#!/bin/bash
set -euo pipefail
base=${1:?Base URL required}
for attempt in $(seq 1 60); do
  if curl --fail --silent --max-time 3 "$base/actuator/health/readiness" | python3 -c 'import json,sys; sys.exit(0 if json.load(sys.stdin).get("status")=="UP" else 1)' 2>/dev/null; then
    break
  fi
  sleep 2
done
curl --fail --silent --max-time 5 "$base/actuator/health/readiness" | python3 -c 'import json,sys; assert json.load(sys.stdin).get("status")=="UP"'
curl --fail --silent --max-time 5 "$base/" | grep -q '<div id="root">'
[[ $(curl --silent --output /dev/null --write-out '%{http_code}' --max-time 5 "$base/api/auth/session") == 401 ]]
echo 'PASS: readiness, bundled frontend and unauthenticated API boundary'
