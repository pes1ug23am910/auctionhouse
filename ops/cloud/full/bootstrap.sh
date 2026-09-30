#!/bin/bash
set -euo pipefail
umask 077
cd /opt/auctionhouse/full
role=$(jq -er .role host-config.json)
install -d -m 0700 /var/lib/auctionhouse/full
if [[ $role == dependency ]]; then
  serial=$(jq -er .dependencyVolumeId host-config.json | tr -d '-')
  device=''
  for attempt in $(seq 1 180); do
    device=$(lsblk -dno PATH,SERIAL | awk -v serial="$serial" '$2==serial {print $1}')
    [[ -z $device ]] || break
    sleep 5
  done
  [[ -n $device && $(wc -w <<< "$device") == 1 ]] || { echo 'Expected dependency EBS volume not attached' >&2; exit 1; }
  # Only the exact declared EBS serial can be initialized; never infer an unused disk.
  filesystem=$(blkid -o value -s TYPE "$device" || true)
  [[ -n $filesystem ]] || mkfs.ext4 "$device" >/dev/null
  [[ -z $filesystem || $filesystem == ext4 ]] || { echo 'Unexpected dependency volume format' >&2; exit 1; }
  install -d -m 0755 /var/lib/auctionhouse/dependencies
  uuid=$(blkid -o value -s UUID "$device")
  grep -Fq "UUID=$uuid " /etc/fstab || printf 'UUID=%s /var/lib/auctionhouse/dependencies ext4 defaults,nofail 0 2\n' "$uuid" >> /etc/fstab
  mountpoint -q /var/lib/auctionhouse/dependencies || mount /var/lib/auctionhouse/dependencies
fi
docker network inspect auctionhouse >/dev/null 2>&1 || docker network create auctionhouse >/dev/null
region=$(jq -er .region host-config.json)
curl --fail --silent --show-error "https://truststore.pki.rds.amazonaws.com/$region/$region-bundle.pem" -o rds-ca.pem
chmod 0644 rds-ca.pem
sha256sum rds-ca.pem > /var/lib/auctionhouse/full/rds-ca.sha256
cat > /etc/systemd/system/auctionhouse-full-restore.service <<'SERVICE'
[Unit]
Description=Restore verified full-stack release and volatile secrets
After=network-online.target docker.service amazon-ssm-agent.service
Wants=network-online.target
Requires=docker.service
RequiresMountsFor=/var/lib/auctionhouse/dependencies
[Service]
Type=oneshot
ExecStart=/opt/auctionhouse/full/release.sh restore
TimeoutStartSec=900
RemainAfterExit=yes
[Install]
WantedBy=multi-user.target
SERVICE
systemctl daemon-reload
systemctl enable auctionhouse-full-restore.service
echo 'Full-stack host initialized; immutable release remains required'
