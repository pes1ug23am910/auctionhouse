# Specification

This document defines the application contract and complete target scope. A
contract is not an implementation or test result; availability must be checked
against the source, selected runtime profile and recorded test reports.

## Purpose and scope

auctionhouse is a non-payment auction application centered on a Java/Spring
modular monolith and PostgreSQL. Complete scope includes:

- Two bid strategies: row locks under READ COMMITTED, and SERIALIZABLE with
  bounded retry; durable idempotency and independent history/invariant checks.
- Seven SQL families on a reproducible, skewed 1M-bid dataset: windows, recursive
  CTEs, percentiles, joins/anti-joins, aggregation, pagination and time/cohort
  analysis, each with a business question and result oracle.
- OIDC login, local authorization and refresh-token rotation/reuse handling
  with explicit application-session/token ownership.
- Transactional outbox, Redpanda transport, consumer groups and a durable local
  notification sink; the selected Apache Kafka profile exercises the same crash contract.
- Cache-aside with actual memcached and a verified pagekv protocol subset;
  the selected Redis comparison uses a separate real backend profile.
- Versioned own-event ingestion into DuckDB, a dbt star schema and
  reconciliation by immutable event identities.
- Accessible React/TypeScript flows with live bids and recovery; a separately
  tested Node.js/TypeScript SSE gateway experiment alongside Java.
- AWS application delivery through shared infrastructure, identity/network
  isolation, TLS, automated release and rollback.
- OpenTelemetry, metrics/logs/traces, Grafana, SLO/error-budget exercises,
  reproducible k6 workloads and one-versus-two-host experiments.

No money moves in this system. Broker/cache compatibility does not establish
integration with a different product. Each candidate product comparison needs
its own decision and actual deployment/test profile.

## Auction rules

Amounts are positive integer minor units. JSON uses `openingPriceMinor`,
`minimumIncrementMinor` and `amountMinor`; every amount is represented as a
Java `long` in `1..9000000000000000`. Fractional values, floating-point coercion
and arithmetic overflow are rejected. The upper bound permits exact integer
representation in JavaScript. A listing has one declared display unit; there
is no exchange-rate or payment behavior.

An auction starts in DRAFT. Its owner publishes it to OPEN before its fixed
deadline. Publication atomically reserves the listing, records announcement
activation intent, changes state and writes an outbox event in PostgreSQL.
There is no remote service call on this transaction's correctness path.

The owner cannot bid. The first accepted amount must be at least the opening
price; later amounts must be at least the current accepted amount plus the
minimum increment. Equal bids do not tie: only the serialized valid transition
can be accepted. The latest accepted bid is the current leader.

The server checks time after acquiring the auction lock. Acceptance requires
`now < endsAt`. The deadline does not extend for late bids. At or after the
deadline, new bids reject even if closure has not yet materialized CLOSED.
Closure records the highest accepted bid as the result, or an unsold result
if no bid was accepted. It is separate from the current-price invariant.

Only the owner can cancel, and only while the auction has no accepted bids.
CLOSED and CANCELLED are terminal. DRAFT and CANCELLED detail, history and
stream access are owner-only; cancelling a draft never publishes its content. The displayed price is the opening
price before any accepted bid; the stored highest bid is null until acceptance.
Afterwards, the displayed price equals the highest accepted amount. Rejected attempts do not change accepted-bid history.

## Request identity and durable outcomes

A bid intent is scoped by `(actorId, auctionId, operation, key)`, with the bid
operation fixed by the endpoint. `Idempotency-Key` must match
`[A-Za-z0-9._:-]{1,128}`. Actor identity comes from the authenticated principal;
the request body cannot select an owner or impersonate another actor. Browser bid submission and status recovery also send
`X-Expected-Actor`, taken from the persisted intent. When supplied, this UUID must
match the authenticated principal or the request returns `409 ACTOR_CHANGED`
before reading or writing an outcome. The header is a precondition, not an
authentication source. It prevents a second tab's account switch from silently
rebinding an offline intent to different cookies. Other clients may omit it,
but must still preserve actor identity when retrying an intent.

The immutable digest covers the canonical semantic payload and namespace.
JSON formatting differences do not create a different request. Reusing the
same namespace/key for another amount is a conflict. A durable outcome contains:

```text
actorId, auctionId, key, amountMinor, accepted, rejection,
bidId, auctionVersion, decidedAt
```

Acceptance identifies the created bid; rejection has no accepted bid and
carries a stable reason. Both outcomes are replayable. A valid retry returns
the stored outcome, including its original time and auction version, even
after closure or later bids. It does not reevaluate current auction rules.
Authentication and ownership checks still apply before replay.

The accepted bid, auction update, durable outcome and outbox event commit in
one transaction. A rejected business decision stores its outcome without
creating a bid or accepted-bid event. Malformed requests and failures before
a durable decision are not promised stored outcomes. An aborted transaction's
response must not escape as a committed decision.

The outcome replay window is 30 days from the committed decision, measured
using the database clock. GET `/api/auctions/bid-policy` publishes that horizon.
After it expires, status and identical retries return `410 INTENT_EXPIRED`.
Actor/auction/key identities and fingerprints remain reserved indefinitely;
a changed payload still returns `409 IDEMPOTENCY_CONFLICT`. The initial storage
policy retains the full row as its tombstone; no cleanup deletes those identities.
Expiry never authorizes retrying the intended bid with a fresh key. An absent status is UNKNOWN, including while an uncommitted request is
in flight; it is not proof the request never ran. Clients reconcile or retry
with the original key and payload.

In-process single-flight is an optimization. Database uniqueness, locking and
transaction boundaries provide correctness across instances. READ COMMITTED
and SERIALIZABLE expose the same observable contract. Deadlock/serialization
retries have bounded attempts and backoff; exhausted transient failures remain
retryable with the original intent key.

## HTTP contract

These are intended routes, not an endpoint coverage report. A route is
available only when its controller and runtime profile exist. Owner-only
operations derive ownership from the authenticated principal.

| Method and route | Contract |
|---|---|
| `POST /api/auctions` | Create a draft with opening price, increment and deadline |
| `GET /api/auctions` | Authorized, deterministically paginated browse |
| `GET /api/auctions/{auctionId}` | Authorized current state and aggregate version |
| `POST /api/auctions/{auctionId}/publish` | Owner publishes before the deadline |
| `POST /api/auctions/{auctionId}/bids` | Exact-unit bid with `Idempotency-Key` |
| `GET /api/auctions/{auctionId}/bids` | Authorized accepted history with stable pagination |
| `GET /api/auctions/{auctionId}/bid-intents/{key}` | This actor's durable outcome or UNKNOWN |
| `POST /api/auctions/{auctionId}/close` | Materialize result once the deadline is reached |
| `POST /api/auctions/{auctionId}/cancel` | Owner cancels without accepted bids |
| `GET /api/auctions/{auctionId}/snapshot` | Consistent authorized snapshot and cursor |
| `GET /api/auctions/{auctionId}/events` | Authorized SSE with resume/gap behavior |
| `GET /api/auth/session` | Current authenticated identity |

Errors distinguish malformed input, unauthenticated access, forbidden actions,
missing resources, key/payload conflicts, business rejection and exhausted
transient retries. Machine-readable codes exclude credentials, SQL internals
and other actors' intent data. Exact statuses/schemas accompany implemented
controllers and their contract tests.

The initial transaction profile may use explicit local/test identities. That
is not OIDC evidence and must not be exposed as authenticated production
access. General access requires the full route/role 401/403 matrix and selected
session, CSRF and CORS policy.

## Streams, delivery and cache

[EVENTS.md](EVENTS.md) defines aggregate versions, consistent snapshots and
immutable source-cut manifests. A global maximum sequence is not a committed
source cut.

The relay may duplicate delivery. A notification consumer commits its local
effect and event deduplication atomically before acknowledgement. The effect
is a database notification record, not an external email delivery guarantee.

Cache-aside applies only to reads with a declared staleness policy. Bids use
authoritative database state. The memcached/pagekv common contract is
get/set/delete with explicit no-expiry or positive relative TTL at most 30
days, documented rounding and unsupported-feature errors. Zero/negative
application duration must not accidentally become permanent storage. Test
fill/invalidation races, timeout fallback and restart. Only verified pagekv
capabilities enter the shared profile.

## Acceptance

[TESTING.md](TESTING.md) defines the acceptance checks. Record commands,
revision/diff, environment, configuration, seeds/input hashes and raw outcomes.
Fixture-only and real end-to-end integration evidence stay distinct. Web,
gateway, delivery and operations remain in scope even when only the transaction
service is implemented.

Accepted decisions and their limits are recorded in the [ADR index](adr/README.md).
The configured cloud topology, external identity-provider registration and real
AWS recovery observations still require deployment evidence; a local decision
or fixture is not proof of those outcomes.
