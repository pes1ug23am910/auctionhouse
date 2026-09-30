# One versus two application hosts

This harness measures the same bounded browse/bid workload through an AWS
Application Load Balancer against one or two actual application EC2 instances.
Both configurations also use one separate dependency EC2 instance, shared
private PostgreSQL/RDS, shared memcached, Redpanda and telemetry. Total EC2 host
counts are therefore **two and three**, respectively. It does not provision
resources, apply Terraform, modify IAM, enable demo login or disable TLS checks.

The implementation has local contract tests and the session workload has a
small real application/database regression. **No real AWS host comparison has
been recorded yet.** CLI doubles establish guard behavior, not cloud routing,
network isolation, capacity or resource costs.

## Preconditions

Use the reviewed [full deployment](../../infra/full/README.md), a valid public
HTTPS origin, its named AWS profile, the explicit isolated full state key and
an unexpired project session. Obtain the spending authorization separately
before provisioning; free credits are not a spending authorization. Complete
the thin/full release acceptance checks before performance work.

Install Node 24, AWS CLI v2, Docker and the frontend's pinned Playwright package
and Chromium. Authenticate three application accounts through the configured
OIDC provider: a seller, a different bidder, and an account granted the local
ADMIN role. The ADMIN session captures source cuts and reads reconciliation
results. Browser/IdP cookies, passwords and refresh tokens are not exported.

The AWS principal needs the full module's inspection document on the exact
project instances, its invocation result, STS identity and the EC2/ALB/RDS/tag
and CloudWatch reads used by `aws.mjs`. The harness sends only the fixed
read-only inspection document, never a free-form remote shell command.

## Record a run

1. Save `terraform -chdir=infra/full output -json comparison_manifest` into a
   private `manifest.json` after applying the approved host count. Preserve the
   immutable release JSON with `appDigest`, `gatewayDigest` and
   `warehouseDigest`, each a `sha256:` digest, from the release that was actually
   verified. Keep the same shared infrastructure, images and settings when
   scaling between one and two application instances.
2. Capture fresh application sessions locally, outside this repository:

   ```sh
   node experiments/hosts/capture-sessions.mjs /private/manifest.json /private/credentials.json
   ```

   Sign in manually in each isolated browser context. The output contains only
   the origin and each account's actor ID, application access token and cookie
   expiry. File creation refuses overwrites. Protect the private directory with
   your user-only OS permissions. Tokens expire; capture again instead of
   sharing a rotating refresh token across load clients. The runner requires
   enough validity for its bounded workload and reconciliation.
3. Put this configuration in the same private directory. Paths resolve relative
   to the configuration file. `outputDirectory` must be a **new** directory with
   an existing parent, outside the source repository.

   ```json
   {
     "label": "one-host-r1",
     "profile": "auctionhouse",
     "manifest": "manifest.json",
     "release": "release.json",
     "credentials": "credentials.json",
     "outputDirectory": "one-host-r1",
     "ratePerSecond": 20,
     "durationSeconds": 60,
     "warmupSeconds": 15,
     "seed": 42,
     "preAllocatedVUs": 40,
     "maxVUs": 80
   }
   ```

   `awsExecutable` may optionally select an installed AWS CLI executable.
   It is invoked directly without a shell. The run rate is bounded to 1–200/s,
   measurement to 10–120 seconds, warmup to 10–60 seconds and clients to 500 VUs.
4. Run `node experiments/hosts/run.mjs /private/config.json`. Both phases create
   fresh auctions with identical opening price and increment. The fixed seeded
   mix is approximately 75% browse and 25% bid, with bounded same-key recovery.
   k6 is pinned by digest and constrained to one CPU and 256 MiB on the load
   generator. Session mode records every terminal bid outcome as a JSON log
   entry; this logging cost is included identically in both topologies.

Before and after load, the runner verifies account/region, ownership/expiry
tags, actual EC2 IDs/types, the private database, exact healthy ALB targets,
disabled stickiness, certificate and host routing. The scoped inspector checks
running immutable images, concrete application/gateway environment settings,
container limits and readiness. A matching manifest hash alone is insufficient.
Private collector counter deltas show whether each application served requests;
missing metrics remain unknown. The workload always verifies HTTPS certificates.

`run.json` records input/script hashes, immutable release, actual host inventory,
client hardware/free memory, warmup/measurement timings, k6 outcomes, inspections
and CloudWatch observations. Raw k6 logs and summaries are retained per phase.
Application tokens never enter summaries or command arguments. A temporary
bidder-only credential file is mounted read-only into k6 and removed afterward.
The original operator credential file remains private until you remove it.

Reconciliation uses authenticated application APIs, not database passwords:
every terminal client outcome must exactly match its durable idempotency
record; accepted bid identities, amounts and versions must match history and
the final snapshot. An explicit committed source cut must contain the exact
auction version sequence and matching sink envelopes, with one effect per event.
Duplicate delivery attempts are recorded and permitted. Fixtures/cuts remain
for inspection; this runner does not delete application data.

## Compare and interpret

Run at least three measurements per topology in alternating order on the same
load generator, with identical seed/rate/duration/warmup/client limits and
release/settings. Save a new manifest after each approved scale change. Wait
for the exact declared healthy target set before each run; failed preflight
does not produce performance evidence. Do not run overlapping experiments.

```sh
node experiments/hosts/compare.mjs /private/comparison.json \
  /private/one-host-r1/run.json /private/two-host-r1/run.json \
  /private/one-host-r2/run.json /private/two-host-r2/run.json \
  /private/one-host-r3/run.json /private/two-host-r3/run.json
```

The comparator rejects differences in workload, artifact hashes, shared
dependencies/database, per-host shapes/limits, actor identities or client
hardware. Fewer repetitions or unknown per-host serving evidence are labelled
preliminary/incomplete. Failed load thresholds remain failed, even when the
correctness oracle passes. Compare offered, started, completed and dropped
work, retry/error counts and p50/p95/p99 together; never select only a favorable
median or omit a slower run.

The shared cache and database grow between runs; warmup does not establish
identical residency. Pool size is per application, so aggregate pool capacity
doubles. Inspect database pressure, CPU credits, CPU/network metrics and load
generator free memory before drawing a scaling conclusion. CloudWatch data can
lag and one-minute buckets overlap short workloads; absent points are unknown.
The collector counters include setup/reconciliation traffic and prove host use,
not balanced routing. This workload does not measure SSE gateway capacity,
live host loss, broker power-loss durability or production users.

Run local contract tests with `node --test experiments/hosts/contracts.test.mjs`.
Keep cloud observations separate from these fixtures and from two local JVM
processes. Retain failures and raw artifacts privately; review them before
publishing any result or promoting a résumé claim.
