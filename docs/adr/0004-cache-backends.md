# 0004 — Compare actual memcached and Redis through a narrow cache contract

**Status:** accepted

## Context

Public auction reads can avoid repeatedly rebuilding payloads, but a cached value cannot decide bids, authorization, closure, durable intent outcomes, or stream recovery. A delayed cache fill can otherwise reintroduce a stale value after invalidation.

The baseline and product comparison need actual servers with an explicit common operation subset. A compatible protocol name alone does not establish Redis or PageKV integration.

## Decision

Use actual memcached as the baseline and select an actual Redis comparison. Both adapters implement get, set and idempotent delete for visible-ASCII keys of 1–250 bytes and values of at most 256 KiB. Expiry is either an explicit no-expiry value or a positive relative duration up to 30 days; fractional seconds round up. Redis uses RESP2 GET, SET with optional EX, and DEL. Memcached uses its basic text protocol with flags zero. Responses, value lengths and operation deadlines are bounded.

Every cached read first checks the authorized current database version. Cache keys include that version, which is never reused. A late old fill can create an old-version entry, but it cannot replace the key used by a later current-version read. A loader that observes a newer version after key selection can cause an extra miss; it cannot make the older version current again. Cache failures return the authoritative loader result; database failures propagate.

Default caching is disabled. The opt-in local profile selects memcached or Redis; local port mappings bind loopback. Cache data is an optimization, not durable business state. The Redis comparison disables snapshot/AOF persistence explicitly, so restart tests evaluate the declared disposable-cache configuration.

The PageKV adapter remains limited to its currently declared no-expiry text subset. A real PageKV integration remains pending an implemented server and capability verification. Local wire fixtures are labelled as fixtures.

## Consequences

Cache-aside counters report hits, misses, loads, fallback events and invalidations with distinct backend labels. Multiple misses may perform duplicate database loads; no cache single-flight guarantee is claimed. Each operation opens its own bounded connection, trading simplicity and isolation of malformed responses for connection overhead.

The native clients deliberately omit pooling, TLS, authentication, sharding, Redis Cluster/Sentinel, CAS, flush operations, and backend-specific data structures. They are appropriate for this isolated local comparison and private service-network experiment; broader deployment requirements need an explicit follow-up design.

Expired or obsolete-version entries may occupy space until eviction or expiry. Explicit no-expiry configurations must account for this, especially before enabling a persistent PageKV server.

## Alternatives considered

A single shared mutable key with delete-after-write was rejected because a read that began before a commit could refill it afterward. Database version lookup preserves a cheap authoritative boundary.

Using Redis branding for a memcached-compatible service was rejected; the comparison uses an actual Redis image and separate protocol implementation. Requiring a pending PageKV server before local cache work was also rejected.

A general third-party client is an alternative for richer authentication, topology and pooling requirements. The small clients here intentionally expose only the tested comparison contract.

## References

- [Memcached basic text protocol](https://docs.memcached.org/protocols/basic/)
- [Redis serialization protocol](https://redis.io/docs/latest/develop/reference/protocol-spec/)
- [Redis SET expiry options](https://redis.io/docs/latest/commands/set/)
