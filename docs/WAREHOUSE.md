# Event warehouse

The warehouse is a local, single-writer DuckDB application under [warehouse/](../warehouse/README.md). It loads committed event envelopes exported from the notification sink and transforms them with dbt. Python 3.13.15, DuckDB 1.5.6, dbt-duckdb 1.11.0, dbt-core 1.12.5 and pytest 9.1.1 were used for the recorded validation; install the complete dependency lock rather than resolving new transitive versions.

The container and CI now pin Python 3.13.16. Its official image index and Linux
AMD64 manifest/config digests were checked on 5 October. The existing results
above remain attached to Python 3.13.15; the updated container still requires
the warehouse tests, container checks and full image security scan.

## Grain and identity

One row in `raw.events` and `analytics.fct_auction_events` means one immutable source `eventId`. It does not mean one delivery, auction, bidder, or sale. A second unique constraint protects `(auction_id, aggregate_version)`. A transaction writes the event effect and its load-attempt record together. A failed attempt-record write rolls back the event effect.

Every arrival gets a separate `raw.load_attempts` row, including accepted, duplicate and quarantined inputs. A replay with the same event ID and canonical JSON content creates another attempt and no additional fact. The same ID with changed content is an `IDENTITY_CONFLICT`; a different ID occupying an existing auction version is a `POSITION_CONFLICT`. Both are quarantined, and the original fact is retained. Sorting JSON object keys and removing insignificant JSON whitespace are the only content normalization rules. Timestamps and user strings are not rewritten during ingestion.

The initial schema accepts the five implemented schema-v1 event types and exactly the documented envelope and snapshot fields, including `winnerId`. It checks canonical UUIDs, timezone-aware timestamps, integer amount bounds, matching auction/version fields, state/type relationships, owner exclusion and final winner consistency. Duplicate JSON keys, nonfinite numbers, unsupported schemas and unexpected fields are rejected. A new event schema needs an explicit validator and model migration before ingestion.

Quarantine retains a stable reason, attempt ID, optional valid event ID, receipt time and SHA-256 of the input. It deliberately does not retain rejected raw payloads, which might contain tokens or other unexpected sensitive fields. Accepted canonical events are retained without automatic deletion. A quarantine fingerprint permits investigation against the original controlled source export; it does not itself reconstruct rejected content.

## Immutable source cuts

A PostgreSQL cut consists of an explicit event ID membership table populated in one repeatable-read transaction. Neither a raw sequence maximum nor equal source/sink row counts defines completeness. The warehouse manifest records the cut ID, sorted unique membership, count, source label, export timestamp and SHA-256 over its canonical content. Registering a changed manifest under an existing cut ID fails. Preserve and reuse the original manifest for every replay of a cut.

The HTTP exporter uses the authorized admin cut/report/source/notification endpoints. Supply its cookie through `AUCTIONHOUSE_ADMIN_COOKIE`; it is never printed, persisted or sent through a redirect. Export requires a reconciled notification sink before pagination. The local Compose exporter creates the same atomic cut and reads source and sink through the PostgreSQL service. Its only database writes are the cut and membership rows. It verifies broker publication metadata, equality of the complete identity sets, and immutable envelope content. PostgreSQL and Java UTC timestamp spellings are compared as instants for that source/sink audit; the original exported envelopes remain unchanged.

Reconciliation reports expected and observed-at-cut IDs, pending or missing IDs, unexpected attempts attributed to the cut, duplicate attempts and duplicate effects separately. Without an explicit declaration that delivery is complete, absent IDs are pending. Later events outside the fixed membership appear as `outsideCutLoaded` and are excluded from missing/extra calculations. A valid event incorrectly attributed to a cut is quarantined. Duplicate attempts are expected under replay; duplicate effects are failures.

## Star schema and queries

| Relation | Meaning |
| --- | --- |
| `dim_auction_versions` | One observed auction/version, with owner, title, terms, state and next observed version time |
| `dim_actors` | Stable owner/bidder IDs and observed roles; no invented demographic attributes |
| `dim_dates` | UTC event dates and calendar attributes |
| `fct_auction_events` | One immutable event, with actor/date/auction-version keys and event-specific measures |
| `mart_daily_activity` | Event counts and sum of accepted offer amounts by UTC day |
| `mart_auction_outcomes` | One closed auction, final offer and winner, including no-bid closure |

A sum of accepted offers includes successive offers on the same auction. It is an activity measure, not revenue, payment volume or a sum of final sale prices. Final offers are only populated on closure events. The dimension records observed versions; it does not invent missing intermediate history.

dbt tests uniqueness, required keys, relationships, permitted event types, exact bid amounts, closure winners and raw/fact set equality. Analyses include per-owner closure outcomes, repeated delivery attempts and a parameterized set reconciliation query. These remain meaningful with empty relations.

## Replacement and recovery

Use `rebuild` with a new database path. It validates retained canonical envelopes, hashes, flattened event columns and cut memberships, copies accepted raw events and attempt/quarantine history, and records a reconstruction ID and event-set hash. It refuses to overwrite an existing database. Run dbt against the replacement and reconcile it before explicitly switching the configured database path. No automatic swap or deletion occurs.

Rebuild is an offline operation: stop the ingestion writer first. DuckDB has a 512 MiB query memory setting and two ingestion threads, but the current Python rebuild and reconciliation materialize identity sets in memory. They are suitable for the measured local workload; no large-scale memory bound is claimed. Raw retention has no automated expiry. A future retention policy must preserve enough immutable identity history to distinguish duplicates from new effects.

## Observed validation

The fixture suite exercises malformed inputs, immutable identity and version conflicts, transactional rollback, source-cut set differences, late-event exclusion, repeated delivery, corrupted retained data and replacement rebuilds. It also runs an actual dbt build against synthetic lifecycle events; fixture results are not broker integration evidence.

A real local cut captured on 1 October 2026 (IST) contained 30 source events and the same 30 notification effects, all with broker publication metadata. Source and sink envelope content matched. Three successive ingestions of that single fixed sink export each completed dbt successfully:

| Run | New facts | Duplicate arrivals | Total facts | Total warehouse attempts |
| --- | ---: | ---: | ---: | ---: |
| 1 | 30 | 0 | 30 | 30 |
| 2 | 0 | 30 | 30 | 60 |
| 3 | 0 | 30 | 30 | 90 |

All three reconciliations had no missing IDs, unexpected IDs, duplicate effects or quarantine records. A fresh replacement preserved the cut and its attempt history. These are three warehouse replays of one real broker-delivered cut, not three independent end-to-end broker workloads, a throughput benchmark, or evidence of production scale. The source cut is `bf864d57-aade-4257-93f7-e95704cf02ef`; its manifest SHA-256 is `1485e4a6f251919e4ebd4a48d93f7c9076c2250eda4b19d16fd4d4249a83f6f4`. Raw exports, dbt logs and run reports stay in the private evidence workspace.
