#!/bin/bash
set -euo pipefail
umask 077
export AWS_PAGER=''
image=${1:?Immutable image required}
cut=${2:?Existing immutable cut required}
work=${3:?Private work directory required}
[[ $cut != 00000000-0000-0000-0000-000000000000 ]]
cd /opt/auctionhouse/full
region=$(jq -er .region host-config.json)
prefix=$(jq -er .parameterPrefix host-config.json)
db=$(jq -er .dbHost host-config.json)
root=/var/lib/auctionhouse/dependencies/warehouse
mountpoint -q /var/lib/auctionhouse/dependencies
[[ ! -e $root/$cut ]] || { echo 'Cut output exists; refusing to overwrite warehouse evidence' >&2; exit 1; }
aws ssm get-parameter --with-decryption --region "$region" --name "$prefix/warehouse" --query Parameter.Value --output text > "$work/warehouse-password"
python3 - "$work/warehouse-password" "$work/pgpass" "$db" <<'PY'
import pathlib,re,sys,os
password=pathlib.Path(sys.argv[1]).read_text().rstrip('\r\n')
if not re.fullmatch(r'[A-Za-z0-9_-]{32,128}',password): raise SystemExit('Invalid staged warehouse credential')
target=pathlib.Path(sys.argv[2]); target.write_text(f'{sys.argv[3]}:5432:auctionhouse:ah_warehouse:{password}\n')
target.chmod(0o600); os.chown(target,10001,10001)
PY
docker run --rm --network auctionhouse --read-only --tmpfs /tmp:size=256m --cap-drop ALL --security-opt no-new-privileges --memory 1536m --cpus 1 \
  -v "$root:/data" -v "$work/pgpass:/run/secrets/pgpass:ro" -v "$PWD/rds-ca.pem:/run/secrets/rds-ca.pem:ro" \
  -e "PGHOST=$db" -e PGPORT=5432 -e PGDATABASE=auctionhouse -e PGUSER=ah_warehouse -e PGPASSFILE=/run/secrets/pgpass \
  -e PGSSLMODE=verify-full -e PGSSLROOTCERT=/run/secrets/rds-ca.pem "$image" "$cut"
