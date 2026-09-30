# Load tests

| Commit | Host | Duration | Workload | Requests/s | Error rate | Browse p95 ms | Bid p95 ms | Change under test |
|---|---|---|---|---|---|---|---|---|

k6 scripts live under `load/`. Result files are stored by commit. A row
compares at most one named change against the previous row on the same
host.
