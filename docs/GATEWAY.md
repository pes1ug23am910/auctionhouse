# Node.js SSE gateway experiment

The Java SSE endpoint is the default delivery topology. The retained Node.js
experiment implements the same auction stream contract independently; its
extra runtime and authorization traffic need measured justification.

## Boundary

The gateway serves only health and authorized auction SSE. Clients supply
their original cookies/bearer header. For each connection, Java verifies the
session and returns an authorized snapshot. There is no gateway login,
refresh-token authority, auction mutation or business-state database.

An upstream stream is shared only within the same actor/auction pair. Every
browser connection still has its own credential and authorization checks.
Sharing a principal ID never authorizes using another client's expired cookie.
Check session and snapshot permission every 750 ms, with a 650 ms request
timeout; deny delivery when successful authorization is over one second old.
Failures close the connection. This bounds cached authorization but can
disconnect legitimate clients when the authority is slow.

If the credential supplying a shared source expires, remove that client.
Choose and revalidate a remaining client's credential before restarting the
source at the group cursor. If none is valid, close the group.

## Ordering, retention and backpressure

The gateway consumes committed Java events with stable IDs and per-auction
versions. It validates schema, aggregate, cursor and payload version.
Duplicate/older input does not change state. A gap, incompatible generation
or changed bytes for a retained identity forces snapshot recovery.

The group ring and each client's pending queue default to 128 events and
256 KiB. Client accounting includes bytes already buffered in Node's writable.
A write returning false pauses draining; the bounded queue either resumes on
drain or disconnects when full. These limits do not include all JavaScript
object/runtime/socket overhead and do not imply a total memory bound.

A resume cursor uses the same generation/auction/version identity as Java.
Replay is used only when the retained suffix is contiguous through the latest
authorized snapshot version. Otherwise deliver a consistent snapshot/cursor.
Restart drops the gateway's transport buffers; clients reconnect and obtain
authoritative state. No business outcome is lost with the gateway process.

Control gap frames are best effort before disconnect. Clients must also treat
plain connection loss as a reason to reconnect/reconcile. Native EventSource
reconnects can forward Last-Event-ID; an initial connection may use the cursor
query because browser EventSource cannot set that header itself.

## Validation boundary

`gateway/npm test` compiles and runs Node's test runner. Tests use actual
local HTTP streaming with a deliberately controlled upstream fixture and
separate fake-writable backpressure cases. They demonstrate this transport's
behavior under those conditions, not real-provider authorization or measured
Java-stack performance.

`gateway/tools/compare.mjs` performs a real-stack paired arrival experiment.
It creates one demo auction, submits a declared number of sequential bids and
observes identical event IDs at the direct Java and gateway endpoints. Its
single collector clock permits paired arrival differences without assuming
clock synchronization with the server. Explicit per-client duplicate, gap,
identity-conflict and disconnect counters accompany missing IDs; incomplete or
unclean delivery suppresses timing distributions. Response-paced sequential
bids are not a constant-arrival capacity workload. POST-start-to-event timing
includes mutation and poll scheduling, while gateway-minus-Java arrival
differences also include independent source poll phases.

The collector supports bounded same-actor fan-out and separate direct/gateway
runs. A native Windows wrapper records process CPU deltas and sampled memory
maxima. Paired mode cannot attribute Java resource cost to one topology.
Gateway health records attempted upstream authorization HTTP requests; Java
internal authorization work is not inferred from those counters. Native TCP
snapshots before/after the run are not peak or active-fan-out measurements.
Separate runs require matched settings, alternating order, repeated samples
and explicit warmup/background-load records. Sampled working set is not an
exact memory peak; client observations of the same event are correlated.
The earlier 20-event comparison predates the nonblocking Java transport and
dependency updates. It remains historical evidence, not a current baseline.
See [the runner commands](../gateway/README.md) for current output semantics.

## Observed local comparison (2026-10-01)

The current nonblocking Java transport and patched dependency artifact were
measured on Windows with Temurin Java 25.0.2, Node 24.15.0 and PostgreSQL 18.6
in Docker Desktop. The host has an Intel Core Ultra 7 155H (22 logical
processors) and 16 GiB RAM. Java ran with `-Xms256m -Xmx512m`, database pool16,
the local profile and no telemetry agent or broker workload. The JAR SHA256 was
`66adab885025155f47144a33e9e94c6cb3e5f545922046de39c02acc52131e27`.

First, the original-sized paired 20-event case delivered all 20 identical event
IDs on both paths with no missing, duplicate, gap or unexpected disconnect.
Its gateway-minus-Java arrival median was 194.4 ms (range -395.0 to 199.4 ms).
Separate source poll phases explain why that difference can be negative; it
is not the gateway's forwarding latency.

Next, each topology received an explicit 100-event warmup with 8 same-actor
clients. Six measured runs followed in the order below, retaining the same
Java/gateway processes and creating a fresh auction for each run. Each run
submitted 100 response-paced accepted bids, waiting 25 ms after each response,
with 1 s idle settle and 3 s between runs. All 600 bids reached all 8 observers:
4800 measured client deliveries, with zero missing IDs, duplicate frames,
identity conflicts, out-of-order frames, gaps or unexpected EOFs. Gateway runs
each opened one shared upstream stream for their 8 clients.

| Run order | POST-start to arrival p50/p95/p99 (ms) | Java CPU (s) | Gateway CPU (s) | Sampled Java/gateway RSS maxima (MiB) | Submit+drain (s) | Gateway authorization HTTP attempts |
| --- | --- | --- | --- | --- | --- | --- |
| java-1 | 305.6/579.4/623.0 | 2.328 | 0.000 | 398.2/63.2 | 7.908 | n/a |
| gateway-1 | 323.0/599.1/620.3 | 2.547 | 0.203 | 389.2/66.9 | 7.947 | 194 |
| gateway-2 | 296.3/556.3/610.7 | 2.203 | 0.250 | 401.8/73.4 | 7.643 | 194 |
| java-2 | 314.2/587.4/624.2 | 3.047 | 0.000 | 418.8/70.2 | 8.301 | n/a |
| java-3 | 307.1/587.9/631.5 | 2.203 | 0.000 | 408.5/70.2 | 8.360 | n/a |
| gateway-3 | 311.9/594.2/622.4 | 1.953 | 0.219 | 397.0/72.7 | 11.984 | 290 |

CPU deltas include fixture setup, idle settle, submissions and drain, while
the duration column includes submissions plus drain only. RSS values are
250 ms sampled maxima, not exact peaks or incremental allocations. The gateway
remained running but idle during direct Java runs; its observed CPU delta was
zero. The sampler also records collector CPU, starting/final memory and
before/after TCP-table snapshots. Those socket snapshots are outside the
active interval and do not establish peak socket counts. Authorization counts
include both identity and snapshot HTTP attempts, successful or failed;
Java's internal authorization work is not inferred from them.

These runs do not show a consistent latency or CPU advantage. Only 826-1684 MiB
host RAM was free at measured-run starts, other user applications remained
active, and JIT/heap/database state carried between runs. The final gateway
submission phase took 11.94 s versus 7.46-8.12 s in the other measured runs; its
median bid-response time rose to 53.2 ms from 28.5-32.4 ms. The cause is unresolved,
so neither averaging it away nor treating it as a topology effect is justified.
The longer interval also explains its additional periodic authorization calls.

Percentiles describe correlated observations of 100 events per run, not 800
independent workload samples. Response pacing is not a fixed arrival-rate
capacity test. This local comparison supports retaining Java as the default:
fan-out sharing is demonstrated, while the extra runtime and authorization
traffic have not shown a repeatable benefit. It does not establish a
production capacity, multi-host result or exact active-socket comparison.

## Observed local faults and accounting (2026-10-01)

A separate instrumented run used the same JAR and gateway server/buffer/parser
artifacts as the comparison above, Java 25.0.2, Node 24.15.0 and a fresh owned
PostgreSQL 18.6 database. Java had a 512 MiB maximum heap and database pool 8;
PostgreSQL had a 512 MiB container limit, one CPU and `pg_stat_statements`.
The production gateway kept its default queue and authorization limits. The
runner supplied local demo credentials to the real rotating-token/session
implementation; this was not a hosted OIDC-provider or AWS test.

Three real-stack cases passed:

| Fault | Observed recovery and durable oracle |
| --- | --- |
| Forced gateway process kill | Two bids were accepted by Java while the gateway was offline. Reconnecting with the original version-4 cursor to a new gateway process returned an exact authoritative version-6 snapshot; the next live event arrived at version 7. All five accepted bids matched durable history and idempotent outcomes. Offline versions 5/6 were recovered as state, not claimed as delivered SSE frames. |
| Refresh rotation and revocation | Both original and rotated credentials streamed before consumed-refresh reuse returned 401. Both streams in that family closed in 324 ms in this single observation; an independent session for the same actor continued. Old and rotated credentials could not reopen streams. After logout and another process restart, the saved independent-session cookie also returned 401. Both bids matched durable history/outcomes. |
| Blocked TCP receiver | An authenticated real socket with a 1024-byte receive buffer stopped reading after HTTP headers. Default gateway limits disconnected that receiver after 80 accepted bids, while its healthy peer received all 80 committed event identities/content. `write(false)` occurred, and resuming reads reached EOF. Reconnecting the original version-2 cursor returned the exact version-82 snapshot. All 80 bids matched durable history/outcomes. |

The slow receiver used a legitimate 3900-character auction description to make
pressure attainable within a bounded local run. The gateway reported one slow
client disconnect and a maximum observed Node writable buffer of 18,772 bytes;
that writable value excludes the pending queue and is not a total memory bound.
The 80-bid count and revocation timing are fixture observations, not thresholds
or latency guarantees. Controlled-upstream tests continue to cover deliberately
duplicated, conflicting and out-of-order source input; the real Java source was
not corrupted to manufacture those conditions.

Six subsequent runs each used eight clients and 20 bids scheduled 250 ms apart,
in alternating order. Every run matched all 160 client frames to committed
PostgreSQL event identities and content: 120 bids and 960 observations. Together
with the fault cases, the run accepted 207 bids. The access-token lookup SELECT
was measured by `pg_stat_statements` before/after each interval; no query values
or credentials were exported. The figures include lookups caused by bids and
stream setup/maintenance during the same interval, not all Java authorization
logic, JVM CPU or HTTP time.

| Order | Access-token SELECT calls / execution ms | Gateway authorization HTTP attempts | Maximum observed established TCP connections: Java / gateway | TCP samples |
| --- | --- | --- | --- | --- |
| java-1 | 322 / 9.19 | 0 | 22 / 7 | 13 |
| gateway-1 | 303 / 11.80 | 146 | 25 / 16 | 12 |
| gateway-2 | 277 / 10.11 | 146 | 25 / 17 | 12 |
| java-2 | 314 / 9.10 | 0 | 25 / 17 | 11 |
| java-3 | 399 / 11.40 | 0 | 25 / 9 | 12 |
| gateway-3 | 360 / 14.03 | 146 | 25 / 16 | 12 |

TCP tables were sampled every 500 ms for the exact process IDs during active
intervals. Counts include database connections, HTTP pools and lingering
keepalives, explaining gateway sockets even during direct Java runs. They are
maximum observed process connections, not continuously observed peaks or a
count of SSE connections. Gateway HTTP/socket/write observations every 100 ms
and database statistics add instrumentation overhead. Intervals lasted about
6.0-6.5 seconds; maximum submission lateness was 15.2-26.7 ms. Fault work warmed
the processes, but there were no matched topology-specific warmups for this
accounting series. JVM/database/pool state carried between runs and other host
applications remained active. The short, variable counts and execution times
do not establish an authorization-cost or socket-efficiency advantage.

[The reproducible runner](../gateway/README.md#real-stack-fault-runner) keeps
these fault and accounting observations separate from the earlier pool-16
latency/resource comparison. Java remains the default. The additional gateway
requires independent service health/routing, credential revalidation, restart
recovery and slow-client disconnect handling; those local failure paths now
have real-stack evidence.

## Remaining comparison scope

The broader comparison still requires:

- Fixed offered-rate and longer-duration work on a controlled host; the local
  response-paced comparison above measures equal event/client counts.
- Continuously observed socket peaks and complete Java authorization CPU/time
  attribution; the active process samples and access-token SELECT accounting
  above have deliberately narrower semantics.
- One-versus-two-host results and the named load balancer/session configuration.
- Hosted operational costs and longer-duration failure behavior; local process,
  credential and slow-client failure paths are documented above.

[ADR 0003](adr/0003-sse-gateway.md) retains Java as default after this local comparison.
