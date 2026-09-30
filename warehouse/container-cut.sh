#!/bin/sh
set -eu
cut=${1:?Existing immutable cut UUID required}
python -c 'import sys,uuid; assert str(uuid.UUID(sys.argv[1])) == sys.argv[1]' "$cut"
test ! -e "/data/$cut"
python /warehouse/tools/capture_local_cut.py --psql --cut "$cut" --output "/data/$cut/source"
python /warehouse/tools/run_cut.py --manifest "/data/$cut/source/manifest.json" --input "/data/$cut/source/sink-events.jsonl" --output "/data/$cut/results" --runs 3
