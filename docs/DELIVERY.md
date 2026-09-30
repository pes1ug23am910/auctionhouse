# Durable event delivery

Auction transitions append an identified event in the same PostgreSQL
transaction as their business effect. The relay claims pending rows with
`FOR UPDATE SKIP LOCKED`, publishes each event, waits for acknowledgement,
then commits its progress. A lost acknowledgement or process crash can
publish the same immutable event again. Consumers do not require global or
cross-relay aggregate ordering: the notification sink records each event
independently, keyed by its immutable event ID.

The sink stores its local notification effect and delivery-attempt record in
one database transaction before acknowledging the Kafka offset. A repeated
identity with identical JSON has one effect and another delivery attempt.
Different JSON under that identity is rejected. Invalid inputs are durably
quarantined by topic/partition/offset before acknowledgement. Database and
other infrastructure failures retry; they are not discarded by a finite
default error-handler recovery policy. This is a local database notification
effect, not a claim about email delivery.

## Local broker

```powershell
docker compose -f compose.yaml -f compose.broker.yaml up -d --wait
.\gradlew.bat bootRun --args='--spring.profiles.active=local,broker'
```

The profile pins Redpanda 26.2.3 by digest. Its one broker has one CPU,
768 MiB internal memory, a 1 GiB container limit and a persistent named
volume. `write_caching_default=false` and automatic topic creation is
disabled; both settings were inspected through `rpk cluster config get`.
The application topic has three partitions, replication factor 1,
`min.insync.replicas=1`, and seven-day retention. Producers use `acks=all`
and idempotence; consumers disable automatic commits and acknowledge only
after the sink or quarantine commit. The single replica cannot survive loss
of its only host/storage volume. Restart tests do not establish that guarantee.

Relay batches default to 16, capped at 100. Each invocation stops starting
new attempts after a 20-second monotonic time budget. An already started send
can additionally spend up to three seconds obtaining producer metadata/buffer
space and three seconds waiting for acknowledgement. This leaves time to
commit completed progress and backoff within the 60-second transaction timeout,
even when a configured batch contains 100 rows. Unattempted rows remain pending.
A failed attempt remains in the database,
with exponential retry delays capped at 256 seconds. Consumer fetch and poll
batches are bounded. A stalled sink causes consumer lag instead of fabricated
success; operators must recover before broker retention removes needed data.

## Reconciliation

An administrator captures `POST /api/admin/event-cuts` to obtain a cut ID.
The capture transaction stores an explicit event-ID set in REPEATABLE READ;
later events never enter that set. `GET /api/admin/event-cuts/{id}` compares
that set with local effects and reports repeated delivery attempts separately.
Its `missing` list means not yet observed at report time, not permanent loss.
Consumers of reports must state their delivery deadline.

`/{id}/events` exports source envelopes; `/{id}/notifications` exports actual
sink envelopes joined to the same set. Both accept `after=<UUID>` and
`limit=1..1000`, ordered by PostgreSQL UUID order. Only an actual sink export
establishes that events traversed the broker. Warehouse manifests additionally
freeze and validate a cryptographic digest of the exported ID set.

## Crash experiment

The opt-in experiment uses an isolated PostgreSQL Testcontainer and a running
real broker. It starts a fresh Java subprocess for each failure and recovery.
`Runtime.halt(73)` terminates the worker without shutdown hooks. All business,
relay and sink code is the production implementation; fault placement lives
only in integration-test code.

```powershell
.\gradlew.bat integrationTest --tests '*BrokerCrashExperimentTest' `
  '-Dauctionhouse.brokerExperiment=true' `
  '-Dauctionhouse.brokers=127.0.0.1:19092' `
  '-Dauctionhouse.experimentOutput=build/experiments/broker' --no-watch-fs
```

Two repetitions of each boundary produce 20 cases:

| Boundary | Recovery assertion |
|---|---|
| Before bid | Original intent can commit once |
| Bid/outbox write before commit | Uncommitted effect rolls back |
| Bid commit before response | Replay recovers the committed result |
| Before broker send | Pending row is published after restart |
| Broker acknowledgement before relay mark | Duplicate transport, one sink effect |
| Relay progress commit | Committed progress is retained |
| Before sink effect | Unacknowledged event is delivered again |
| Sink effect before commit | Effect and attempt roll back together |
| Sink commit before consumer acknowledgement | Redelivery is observed, effect remains unique |
| After consumer acknowledgement | Restart retains completed effects |

On 2026-10-01, all 20 cases passed on Windows 11, Temurin 25.0.2,
Docker Desktop 29.8.1/Linux containers and the declared single-broker profile.
Every successful report compared exactly three expected event IDs, zero
missing and zero unexpected IDs, and one accepted bid. The two deliberate
duplicate boundaries additionally required observed repeated delivery attempts.
These were process failures, not host power-loss or multi-replica failures.

The first run retained six failures: its 30-second consumer deadline was
shorter than the default group session timeout, and its recovery condition
could exit on complete database effects before draining a redelivery.
The final fixture explicitly uses a six-second session timeout and one-second
heartbeat, and requires broker offsets to be drained. Production consumer
timeouts were not changed to make the experiment pass. JSON per-case reports,
worker logs and JUnit results form the raw local evidence.

## Broker interruption and backpressure

The separately selected local interruption test verifies the exact Docker
Compose project/service labels before sending SIGKILL to the Redpanda process.
It preserves the broker volume and uses an isolated PostgreSQL Testcontainer
plus a unique disposable topic. Do not run it alongside another experiment
that relies on this broker.

```powershell
.\gradlew.bat integrationTest --tests '*BrokerOutageExperimentTest' `
  '-Dauctionhouse.brokerOutage=true' `
  '-Dauctionhouse.experimentOutput=build/experiments/broker-outage' --no-watch-fs
```

The 2026-10-01 run passed. Two events were acknowledged before the broker
process was killed. Eleven bids then committed while it was unavailable;
one bid ran concurrently with the blocked relay and took 27.7891 ms. A
four-row relay invocation returned after 12046.7724 ms, retaining four failed
attempts and leaving seven rows untouched. After restart, all thirteen source
IDs reached thirteen unique sink effects, with no missing or unexpected IDs;
the measured restart-to-reconciliation interval was 8159.4697 ms. These are
observations from one local run, not latency objectives or a replicated
durability guarantee. SIGKILL with a preserved volume does not simulate
physical power loss.

## Actual Apache Kafka comparison

`compose.kafka.yaml` defines a separate Apache Kafka 4.3.1 JVM broker, pinned
by the official multi-platform image digest. It uses a combined KRaft
broker/controller, one replica, one CPU, a 512 MiB heap, a 1 GiB container
limit, loopback port29092 and its own persistent volume. Automatic topic
creation is disabled. `log.flush.interval.messages=1` requests a disk flush
after each message; this deliberate single-node experiment differs from the
usual replicated Kafka deployment. See the official [topic configuration
reference](https://kafka.apache.org/43/configuration/topic-configs/).

```powershell
docker compose -f compose.yaml -f compose.kafka.yaml up -d --wait kafka
.\gradlew.bat integrationTest --tests '*BrokerCrashExperimentTest' `
  '-Dauctionhouse.brokerExperiment=true' `
  '-Dauctionhouse.brokers=127.0.0.1:29092' `
  '-Dauctionhouse.experimentOutput=build/experiments/kafka' --no-watch-fs
```

On 2026-10-01 all twenty application-process crash cases passed against
Apache Kafka using the same committed-source-set oracle as Redpanda. Each
case recovered three expected events, with zero missing or unexpected IDs,
one accepted bid and three unique notification effects. The deliberate
acknowledgement-gap cases also observed repeated transport attempts. This
run used the dependency-patched application build; the earlier Redpanda
measurement retains its own build baseline. No throughput ranking follows
from these correctness checks.

The initial Kafka startup failed because a volume mounted at a newly created
temporary path was owned by root. The working profile mounts its separate
volume at the image's existing application-owned `/var/lib/kafka/data` path;
the broker continues to run as its non-root application user. The failed
startup log and successful per-case reports were retained.

The broker interruption test above is restricted to Redpanda and is not
included in this Kafka comparison. The default remains Redpanda. Neither
single-node configuration proves replicated availability or physical
power-loss durability.
