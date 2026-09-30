#!/bin/bash
set -euo pipefail
umask 077
export AWS_PAGER=''
work=${1:?Private work directory required}
cd /opt/auctionhouse/full
config=host-config.json
region=$(jq -er .region "$config")
prefix=$(jq -er .parameterPrefix "$config")
root=/var/lib/auctionhouse/dependencies
mountpoint -q "$root" || { echo 'Persistent dependency volume is not mounted' >&2; exit 1; }
token=$(curl --fail --silent --max-time 3 -X PUT -H 'X-aws-ec2-metadata-token-ttl-seconds: 60' http://169.254.169.254/latest/api/token)
private=$(curl --fail --silent --max-time 3 -H "X-aws-ec2-metadata-token: $token" http://169.254.169.254/latest/meta-data/local-ipv4)
unset token
[[ $private =~ ^10\.74\.[0-9]+\.[0-9]+$ ]]
for name in redpanda tempo prometheus grafana telemetry warehouse; do install -d -m 0750 "$root/$name"; done
chown 101:101 "$root/redpanda"
chown 10001:10001 "$root/tempo" "$root/telemetry" "$root/warehouse"
chown 65534:65534 "$root/prometheus"
chown 472:472 "$root/grafana"
install -d -m 0755 /run/auctionhouse-full-dependencies
aws ssm get-parameter --with-decryption --region "$region" --name "$prefix/grafana" --query Parameter.Value --output text > /run/auctionhouse-full-dependencies/grafana
chown 472:472 /run/auctionhouse-full-dependencies/grafana
chmod 0400 /run/auctionhouse-full-dependencies/grafana
images=(
  'docker.redpanda.com/redpandadata/redpanda:v26.2.3@sha256:9e83cfa99278f30d0133271c26bf670cd69c94ffa6ba0b42830dd0c3bd9dcfd9'
  'memcached:1.6.45@sha256:405a445c7c81bca205850426288baa5655c72859ae31d3de3fa823e6482be4ad'
  'grafana/tempo:3.1.0@sha256:3076b8dcdfb32fd6bc5ccef85e7b7313e6199b9cb84366257fc17ecb696db5fd'
  'otel/opentelemetry-collector-contrib:0.161.0@sha256:fd328de2552466ad78385e1b1289c3f2402b1c45f265b252aab1955b42845ac1'
  'prom/prometheus:v3.15.0@sha256:efd719c99d83b060d9daefdcf00360461adf279f45ef5391f8d111892118753e'
  'grafana/grafana:13.2.3@sha256:b28bae15e219c998fb0e0424ed724930cc61b1f61fb404d47c862f9a23f9e572'
)
for image in "${images[@]}"; do docker pull "$image" >/dev/null; done
# Pull every pinned artifact and retrieve required secrets before touching any running service.
docker rm -f redpanda memcached tempo collector prometheus grafana >/dev/null 2>&1 || true
base=(--network auctionhouse --restart unless-stopped --cap-drop ALL --security-opt no-new-privileges --log-opt max-size=10m --log-opt max-file=3)
docker run -d --name redpanda "${base[@]}" --memory 1536m --cpus 1 -p "$private:9092:9092" \
  -v "$root/redpanda:/var/lib/redpanda/data" "${images[0]}" redpanda start --mode dev-container --smp 1 --memory 1G --reserve-memory 0M \
  --kafka-addr 0.0.0.0:9092 --advertise-kafka-addr "$private:9092" \
  --set redpanda.write_caching_default=false --set redpanda.auto_create_topics_enabled=false >/dev/null
docker run -d --name memcached "${base[@]}" --read-only --memory 128m --cpus 0.5 -p "$private:11211:11211" \
  "${images[1]}" memcached -m 64 -U 0 -l 0.0.0.0 >/dev/null
docker run -d --name tempo "${base[@]}" --user 10001:10001 --read-only --tmpfs /tmp --memory 768m --cpus 1 \
  -v "$PWD/observability/tempo.yaml:/etc/tempo.yaml:ro" -v "$root/tempo:/var/tempo" \
  "${images[2]}" -target=all -config.file=/etc/tempo.yaml >/dev/null
docker run -d --name collector "${base[@]}" --user 10001:10001 --read-only --memory 256m --cpus 1 -p "$private:4318:4318" -p 127.0.0.1:9464:9464 \
  -v "$PWD/observability/collector.yaml:/etc/otel/config.yaml:ro" -v "$root/telemetry:/var/otel" \
  "${images[3]}" --config=/etc/otel/config.yaml >/dev/null
docker run -d --name prometheus "${base[@]}" --read-only --memory 256m --cpus 1 \
  -v "$PWD/observability/prometheus.yaml:/etc/prometheus/prometheus.yaml:ro" -v "$PWD/observability/alerts.yaml:/etc/prometheus/alerts.yaml:ro" \
  -v "$root/prometheus:/prometheus" "${images[4]}" --config.file=/etc/prometheus/prometheus.yaml \
  --storage.tsdb.retention.time=24h --storage.tsdb.retention.size=256MB >/dev/null
docker run -d --name grafana "${base[@]}" --read-only --tmpfs /tmp --memory 384m --cpus 1 -p 127.0.0.1:3300:3000 \
  -e GF_SECURITY_ADMIN_USER=auctionhouse -e GF_SECURITY_ADMIN_PASSWORD__FILE=/run/secrets/grafana \
  -e GF_AUTH_ANONYMOUS_ENABLED=false -e GF_USERS_ALLOW_SIGN_UP=false -e GF_ANALYTICS_REPORTING_ENABLED=false \
  -e GF_ANALYTICS_CHECK_FOR_UPDATES=false -e GF_ANALYTICS_CHECK_FOR_PLUGIN_UPDATES=false -e GF_PLUGINS_PREINSTALL_DISABLED=true \
  -e GF_PLUGINS_PREINSTALL_AUTO_UPDATE=false -e GF_PLUGINS_PLUGIN_ADMIN_ENABLED=false -e GF_PLUGINS_PUBLIC_KEY_RETRIEVAL_DISABLED=true \
  -e GF_PATHS_PLUGINS=/tmp/auctionhouse-plugins -v /run/auctionhouse-full-dependencies/grafana:/run/secrets/grafana:ro \
  -v "$PWD/observability/grafana/provisioning:/etc/grafana/provisioning:ro" \
  -v "$PWD/observability/grafana/dashboards:/var/lib/grafana/dashboards:ro" -v "$root/grafana:/var/lib/grafana" "${images[5]}" >/dev/null
for attempt in $(seq 1 60); do
  if docker exec redpanda rpk cluster health --exit-when-healthy >/dev/null 2>&1; then break; fi
  sleep 2
done
docker exec redpanda rpk cluster health --exit-when-healthy >/dev/null
docker exec redpanda rpk topic describe auctionhouse.events.v1 >/dev/null 2>&1 || \
  docker exec redpanda rpk topic create auctionhouse.events.v1 --partitions 3 --replicas 1 >/dev/null
docker exec redpanda rpk topic alter-config auctionhouse.events.v1 --set write.caching=false >/dev/null
for name in redpanda memcached tempo collector prometheus grafana; do [[ $(docker inspect "$name" --format '{{.State.Running}}') == true ]]; done
curl --fail --silent --max-time 5 http://127.0.0.1:3300/api/health >/dev/null
echo 'Dependency processes ready; real request trace and immutable-cut warehouse checks remain release acceptance steps'
