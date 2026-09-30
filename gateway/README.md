# SSE gateway experiment

An independent Node.js/TypeScript fan-out implementation for the auction
service. Java remains the default SSE endpoint. This package owns transport
and bounded queues; PostgreSQL/Java own authorization and auction decisions.

## Run and verify

Requires Node 24 or newer and a reachable Java backend.

```sh
npm ci
npm test
npm run build
npm start
```

The executable binds `127.0.0.1:3001`; `PORT` changes the port.
`AUCTIONHOUSE_UPSTREAM` defaults to `http://127.0.0.1:8080`.
Only `GET /health` and `GET /api/auctions/{id}/events` are served.
The stream accepts a cursor query or `Last-Event-ID`, with the header taking
precedence. Route other application requests directly to the Java service.

No permissive cross-origin policy is enabled. Browser use requires a same-origin
reverse proxy for this one event route; keep the Java route as the baseline.

## Authentication and transport

Every client forwards its original Cookie/Authorization headers for upstream
session and authorized-snapshot checks. Cookies are held only for the lifetime
of that connection and are never logged or made into a gateway session.
Different actors never share a source.

Same-actor/same-auction clients share an upstream stream, but each credential
is revalidated independently every 750 ms. A delivery is denied if the last
successful validation is older than one second. Expired/revoked/forbidden or
unavailable checks disconnect the client. If that client supplied the upstream
credential, a separately revalidated remaining client supplies the replacement.
This may create more upstream authorization requests than direct Java SSE.

A ring retains at most 128 events/256 KiB per group. Each client also has a
128-event/256-KiB pending limit, including the writable's buffered bytes.
Slow clients disconnect; limits are transport-buffer bounds, not a total
process-memory guarantee. Resume replays a complete retained suffix; otherwise
the gateway returns an authorized consistent snapshot. Duplicates/old input
are ignored, changed bytes for a retained identity or a version gap disconnect
for snapshot recovery.

## Tests and comparison

The Node test suite uses actual local HTTP streams with a controlled upstream
fixture. It covers grouping, original-credential authorization, revocation,
permission changes, source-credential replacement, resume, gaps, duplicate
delivery, restart and a fake writable that returns false under backpressure.
Fixture transport evidence does not establish real Java integration or an
operational benefit.

A paired real-stack collector is included:

```sh
node --test tools/comparison.test.mjs
node tools/compare.mjs
```

Supply `AUCTIONHOUSE_DEMO_PASSWORD` through the environment, never command-line
arguments. Java/PostgreSQL and the gateway must be running on loopback. The
default creates one seller fixture, opens one observer on each path, waits one
second for setup, then submits 20 sequential bidder requests, waiting 25 ms
after each response. This is a response-paced workload, not a fixed arrival
rate or capacity test.

The report preserves each observer's event IDs, versions and collector-clock
arrival times, plus setup, idle-settle, submission and drain durations. Gateway
health snapshots include authorization HTTP attempts: every identity/snapshot
request increments the counter, including rejected or failed attempts. Missing events, conflicting IDs, duplicates, gaps, malformed
frames or unexpected disconnects prevent clean timing distributions. It does
not automatically reconnect and hide a disconnect. POST-start-to-arrival
timing includes the write, database/poll scheduling and transport; a stream
event may arrive before its POST response. Paired gateway-minus-Java times
compare the same event/client pair on one clock. Separate Java source streams
have independent poll phases, so a negative difference is not proof that
proxying itself reduced latency.

Configure the bounded workload with:

| Variable | Default | Meaning |
| --- | --- | --- |
| `AUCTIONHOUSE_COMPARISON_MODE` | `paired` | `paired`, `java` or `gateway` observers |
| `AUCTIONHOUSE_COMPARISON_CLIENTS` | `1` | Same-actor clients per selected path, maximum 32 |
| `AUCTIONHOUSE_COMPARISON_EVENTS` | `20` | Sequential accepted bids, maximum 200 |
| `AUCTIONHOUSE_COMPARISON_INTERVAL_MS` | `25` | Delay after each bid response |
| `AUCTIONHOUSE_COMPARISON_SETTLE_MS` | `1000` | Idle interval after opening observers |

For native Windows application processes, a resource wrapper preserves both
collector output and interval samples in a new directory:

```powershell
.\tools\Measure-Comparison.ps1 -JavaProcessId <verified-java-pid> `
  -GatewayProcessId <verified-gateway-pid> -OutputDirectory <new-private-directory> `
  -Mode paired -Clients 1 -Events 20 -IntervalMs 25
```

Verify both process identities before supplying their IDs. The wrapper records
CPU deltas and sampled working-set/private-memory/handle maxima every 250 ms.
It includes login, auction setup, idle settle and drain time. It does not
capture exact memory peaks, container resource use or database CPU. TCP-table
snapshots are taken before and after the collector interval; they are not
active fan-out or peak socket counts. Host free RAM and process initial/final
working sets accompany the samples. Handle count is not socket count. The
collector's own CPU interval is reported separately.

Paired mode exercises both paths at once, so its Java CPU cannot be allocated
between topologies. For an exploratory resource comparison, run separate
`java` and `gateway` modes with identical clients/events/cadence and application
settings, alternating order across repeated runs. Record warmup, initial heap,
database state, current JAR/source hashes and other background workloads.
Java and gateway processes can retain memory/JIT state between runs; these
intervals alone do not establish incremental allocation or a tuning benefit.
Samples from many clients receiving one event are correlated.

This mutates the local demo database by creating explicitly named comparison
auctions. It does not delete fixtures. Raw result files belong outside public
source. See [the design](../docs/GATEWAY.md) for the retained wider comparison.
