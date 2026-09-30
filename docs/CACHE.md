# Cache behavior and backend comparison

Caching is disabled by default. The optional cache holds public OPEN/CLOSED auction detail payloads. Every eligible read first checks the current database version and access rules. Draft and cancelled details bypass it. Lists, bids, intent recovery, mutations, authorization and streams use PostgreSQL directly.

## Start the local cache profiles

Run from the repository root:

```powershell
docker compose -f compose.yaml -f compose.cache.yaml up -d postgres memcached
$env:SPRING_PROFILES_ACTIVE = 'local,cache'
.\gradlew.bat bootRun
```

The cache profile selects memcached by default. For the actual Redis comparison:

```powershell
docker compose -f compose.yaml -f compose.cache.yaml up -d postgres redis
$env:SPRING_PROFILES_ACTIVE = 'local,cache'
$env:AUCTIONHOUSE_CACHE_BACKEND = 'redis'
.\gradlew.bat bootRun
```

Existing application authentication and database configuration still apply. Compose exposes PostgreSQL and cache ports on loopback. Defaults are host 127.0.0.1, automatic port 11211 for memcached/PageKV or 6379 for Redis, operation deadline 500 ms, value limit 262144 bytes and TTL 30 seconds. Override the host and port with AUCTIONHOUSE_CACHE_HOST and AUCTIONHOUSE_CACHE_PORT. Spring properties auctionhouse.cache.timeout, max-value-bytes, ttl and no-expiry configure the remaining settings.

An explicit no-expiry value is separate from positive expiry. Zero, negative and relative TTLs above 30 days are rejected. Fractional seconds round upward. This avoids accidentally treating zero as permanent or a duration above 30 days as a memcached Unix timestamp. No-expiry does not imply durable storage or protection against eviction.

## Shared contract

| Backend | Implementation | Verified expiry | Local container |
| --- | --- | --- | --- |
| memcached | Basic text get/set/delete, flags zero | Positive relative TTL and no expiry | 1.6.45 |
| Redis | Actual RESP2 GET/SET EX/DEL | Positive relative TTL and no expiry | 8.10.2 |
| PageKV | Text-protocol adapter | No expiry only; fixture verified | Real server integration pending |

All adapters accept 1–250 visible-ASCII key bytes, binary values up to 256 KiB, empty values and idempotent deletion. Each operation opens a separate socket with connect/read bounds and a total deadline. Responses and declared payload sizes are checked before allocation. Unsupported, corrupt, truncated and oversized responses fail closed to the database loader.

The image references in compose.cache.yaml and integration tests include immutable manifest digests. The Redis comparison explicitly disables snapshot and AOF persistence; both tested profiles lose their cache on process restart. These clients do not implement pooling, TLS, authentication, clustering, sharding, CAS or backend-specific structures.

PageKV currently has a documentation/configuration scaffold and no implemented server in the inspected sibling checkout. Its declared text subset requires exptime zero. Selecting pagekv therefore requires auctionhouse.cache.no-expiry=true; positive expiry is rejected before network I/O. Local protocol fixtures establish adapter behavior only, not interoperability with an actual PageKV process. No PageKV benchmark or real-server pass is claimed.

## Versioned cache keys and stale fills

AuctionBrowse uses keys of the form auction:v1:<auction UUID>:<database version>. A read must obtain the authorized current version from PostgreSQL before cache lookup. Auction versions only advance and are never reused.

If an old read fills version 12 after a writer commits version 13, later reads select the version-13 key. The late fill cannot overwrite that current key. If the loader observes a newer version than the initial lookup, the authorized database result is returned without filling the earlier key. A loader result must match the exact auction ID and stamped version and remain OPEN or CLOSED before entering the shared cache. This prevents an owner read racing with cancellation from caching private state under a formerly public key. A request concurrent with a commit may return the earlier state observed by its version lookup; this is not a promise of a snapshot taken at response transmission time.

The wrapper applies the same ID, exact-version and public-status checks to cache hits; an ineligible hit is invalidated and reloaded through the actor-authorized database path. Invalidation is an optional space-management action; correctness does not depend on delete-after-write winning a race. Old keys disappear by expiry or eviction. No-expiry configurations need explicit capacity management.

Cache errors invoke the authoritative loader once. A failed cache read skips the subsequent cache write, avoiding two network deadlines on one outage path. Corrupt decoded payloads reload and replace the value. Loader and version-lookup failures propagate; a cache cannot hide a database failure. Concurrent misses can duplicate loads; no single-flight behavior is claimed.

## Metrics and checks

Micrometer counters use the backend tag: auctionhouse.cache.hit, miss, load, fallback and invalidation. Fallback counts cache failure events rather than unique requests; one request can produce multiple events. The default actuator exposure remains health only; metrics export is configured separately.

```powershell
.\gradlew.bat test --tests 'io.auctionhouse.cache.*'
.\gradlew.bat integrationTest --tests '*CacheStoreIntegrationTest'
```

The local 2026-10-01 check passed 25 unit invocations and 10 real-container integration invocations. Unit checks cover protocol framing and allocation limits, explicit expiry, error fallback, defaults, metrics, and a deterministic delayed-fill/invalidation race. The integration suite exercises actual memcached and Redis binary/empty/maximum values, overwrite/delete, positive expiry, no-expiry survival, restart loss and outage fallback.

An initial integration run failed because Docker reassigned random published ports after restart while the fixture reused Testcontainers' cached mapping. The fixture now inspects the current Docker port binding after each restart. The corrected run passed all ten cases. This was an experiment-fixture failure; both outcomes are retained in the private evidence record. Cache throughput and latency comparison, HTTP cache behavior under load, and actual PageKV interoperability remain separate checks.

See [ADR 0004](adr/0004-cache-backends.md), the [memcached basic protocol](https://docs.memcached.org/protocols/basic/), [Redis RESP](https://redis.io/docs/latest/develop/reference/protocol-spec/) and [Redis SET expiry](https://redis.io/docs/latest/commands/set/).
