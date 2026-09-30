# 0003 — Keep Java SSE as default while evaluating a Node.js gateway

**Status:** accepted

## Context

The React and mobile contracts require authorized live state, stable cursors,
consistent snapshot recovery and bounded slow-client handling. A separate
Node.js fan-out runtime is a useful implementation experiment, but adds
deployment, memory, sockets and authorization traffic.

## Decision

Retain direct Java SSE as the default topology. Implement the Node.js gateway
as an independently tested experiment with original-credential authorization,
per-actor/per-auction sharing, bounded buffers, replay and restart recovery.

The gateway delegates identity and auction permission to Java for every
client and periodically revalidates them; it does not mint sessions or
become an authorization authority. Authorization-check failure disconnects
rather than preserving stale access.

Use the same committed event/cursor protocol on both paths. Measure paired
delivery, resource use and operational complexity on the same workload before
selecting a different default. Retain the experiment and its evidence even
if the extra runtime is not justified.

## Consequences

Application correctness and default deployment do not depend on the gateway.
The experiment exposes fan-out, backpressure and credential-lifecycle trade-offs
directly. Sharing sources within one actor can reduce stream connections but
does not remove per-client authorization cost.

In-memory retention is bounded and can be lost on restart. Authoritative
snapshots provide recovery; missing intermediate events are not represented
as delivered history. Revocation polling bounds cached permission and can
disconnect otherwise valid clients when the upstream authority is slow.

No gateway performance advantage or production-scale claim follows from its
existence or fixture tests. A later topology decision needs recorded results.

## Alternatives considered

- Make Node the default immediately: lacks evidence that added complexity
  improves this application.
- Discard the separate runtime: removes the independent fan-out and
  backpressure comparison.
- Share sources across actors using one privileged cookie: creates an
  authorization boundary the experiment does not own.
- Unbounded client queues: permits slow clients to grow memory without limit.
- Treat gateway restart as business-state loss: business authority remains
  in PostgreSQL/Java, so snapshot/cursor recovery is the appropriate boundary.

## Current comparison outcome (2026-10-01)

Retain the existing decision after the current Java transport comparison.
All 600 measured bids reached all 8 observers across three runs per topology,
with no missing/duplicate/gap/EOF outcomes. Each gateway run shared one
upstream stream among 8 same-actor clients, but generated 194-290 additional
identity/snapshot HTTP attempts. Local latency and CPU varied without a
consistent advantage; memory pressure, response pacing, retained JVM state
and the slower final gateway interval prevent a causal or capacity claim.

[The measured settings and per-run table](../GATEWAY.md#observed-local-comparison-2026-10-01)
preserve those limitations. Java needs one application runtime; the gateway
adds a deployable service, health/routing policy, retained transport state and
periodic dependency on the authorization authority. The demonstrated fan-out
sharing does not yet justify those additional operational responsibilities.
The subsequent [real-stack fault/accounting run](../GATEWAY.md#observed-local-faults-and-accounting-2026-10-01)
proved forced process restart with offline bids and authoritative cursor
recovery, real credential-family revocation/nonresurrection, and bounded
disconnect of a blocked TCP reader while its healthy peer retained every event.
Six short matched runs also recorded actual access-token database lookups and
active process TCP samples. Those narrower metrics do not establish a repeatable
cost advantage or exact socket peaks. Multi-host results and longer controlled
capacity/operational measurements remain separate work.
