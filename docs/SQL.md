# SQL catalogue

The catalogue contains seven PostgreSQL query families with independent result oracles and a reproducible index experiment. It uses a dedicated synthetic benchmark database, separate from application migrations and live application data. Bid amounts are exact integer units; the sum of accepted bid amounts is activity volume, not revenue or payments.

## Run

Start the project's PostgreSQL service, then run from the repository root with Python 3:

~~~powershell
docker compose up -d --wait postgres
python sql/run_catalogue.py --bids 1000000 --seed 20261001 --repetitions 3
~~~

The runner creates the dedicated database `auctionhouse_sql_bench` if absent, then creates new uniquely named benchmark and oracle schemas. It refuses an existing schema or nonempty output directory and never drops or truncates existing data. A run remains available for inspection. Default reports go to the ignored `build/sql-catalogue/<UTC timestamp>/` directory; use `--output <empty-directory>` to retain reports elsewhere.

The output contains exact executed SQL, command arguments, exits, stdout/stderr, source/input hashes, server configuration, expected and observed results, and JSON `EXPLAIN (ANALYZE, BUFFERS, SETTINGS)` plans. Each query executes once for correctness/warmup before three recorded plan samples. Unit or application integration tests do not invoke this million-row experiment.

## Data and correctness

The recorded seed produces 1,000,000 accepted bid rows, 12,000 auctions, 2,100 actor identities, and a 31-category binary hierarchy. Half the bids target 100 hot auctions; the remainder target 9,900 other auctions. Two thousand auctions have no bids. Seller identities and bidder identities are disjoint. Amounts strictly increase in increments of ten within each auction; timestamps advance by one second per generated bid from 2026-01-01 UTC. The generator deliberately has correlated activity and one acquisition-day cohort; it is not a model of real user behavior.

`oracle_fixture.sql` supplies a small manually enumerated fixture. The runner checks all seven expected answers, including the empty-listing case, different increments, percentile interpolation, and distinct-bidder denominators. A separate Python model computes every large-fixture query result using counters, sets, ordered events, and arithmetic; it does not query PostgreSQL for its expected answers. Both pre-index and post-index results must exactly match this model.

| Family | Business question and query | Result contract |
|---|---|---|
| Windows | [Bid progression](../sql/queries/01_windows.sql): how did each accepted offer increase auction 1's price? | Ordered bid identity, amount, and delta from preceding bid or opening amount |
| Recursive CTE | [Category subtree](../sql/queries/02_recursive_categories.sql): how many listings and accepted bids belong to each category under category 2? | Category depth and counts, including categories/listings with no bids |
| Percentiles | [Time to next bid](../sql/queries/03_percentile_wait.sql): what are the median and p95 observed within-auction waiting times? | Interpolated continuous percentiles; final bids with no observed successor are excluded |
| Anti-join | [Listings without bids](../sql/queries/04_unsold_listings.sql): which of seller 2's listings have no accepted offers? | Ordered auction identities and opening amounts; no false match from another auction |
| Aggregation | [Seller activity](../sql/queries/05_seller_activity.sql): how many listings, bids, distinct bidders, and offered units does each seller have? | Listings without bids retained; bid volume is not sales revenue |
| Keyset pagination | [Next bid page](../sql/queries/06_keyset_page.sql): retrieve auction 1's next 50 bids after an exact cursor | Strict `(placed_at, bid_id)` boundary and stable ordering; no offset scan |
| Time/cohorts | [Bidder activity](../sql/queries/07_bidder_cohorts.sql): what fraction of each first-bid cohort returned on later observed UTC days? | Distinct active identities divided by cohort population; future days remain unobserved |

## Recorded experiment

Observed 2026-09-30/2026-10-01 IST on the local Windows 11 amd64 Docker host, PostgreSQL 18.6, with the database container limited to two CPUs and 1 GiB memory (2 GiB memory-plus-swap limit). Server settings included 128 MiB shared buffers, 4 MiB work memory, JIT enabled, and up to two parallel workers per gather.

Application builds, load tests, container startup and image pulls were paused for measurement; light editing and another package installation could continue. These are exploratory local measurements. They are server-reported execution times, excluding client transport and result serialization.

| Query | Before median ms | After median ms | Change | Indexes selected after |
|---|---:|---:|---:|---|
| Bid progression | 18.858 | 2.717 | -85.59% | `bids_auction_order`, auction primary key |
| Category subtree | 387.174 | 272.937 | -29.51% | `bids_auction_order` |
| Waiting-time percentiles | 1005.516 | 677.680 | -32.60% | `bids_auction_order` |
| Listings without bids | 84.559 | 0.669 | -99.21% | `auctions_seller_order`, `bids_auction_order` |
| Seller activity | 668.949 | 261.650 | -60.89% | `auctions_seller_order`, `bids_auction_order` |
| Keyset page | 18.165 | 0.075 | -99.59% | `bids_auction_order` |
| Bidder cohorts | 841.454 | 793.444 | -5.71% | None |

All seven small-fixture checks passed; all fourteen large-fixture comparisons passed; 42 plan samples were recorded. No median regression was observed in this run. The cohort query did not select an added index, so its small timing difference is not attributed to an index benefit.

The baseline already includes primary-key indexes and foreign-key constraints. The four additional indexes are defined in [indexes.sql](../sql/indexes.sql). Total relation storage grew from 92,102,656 to 183,492,608 bytes: an extra 91,389,952 bytes (87.16 MiB). Index DDL took 1.389 seconds wall time, and seed insertion took 9.403 seconds wall time; these two durations include the local command boundary. Ongoing write amplification was not measured.

`bids_bidder_time` and `auctions_category` were not selected by any recorded query. Their storage and maintenance cost remains a real trade-off; this experiment does not recommend keeping every candidate index in an application schema.

The run used seed `20261001`, benchmark schema `run_20261001_001`, three samples per phase, and the exact SQL in this catalogue. Before/after order was not randomized, caches were not cleared, and `ANALYZE` sampling or host activity can change later plans and timings. Re-run the command to obtain complete local reports; the table is a historical result, not a performance guarantee.

The planner and measurement interpretation follow PostgreSQL's [EXPLAIN documentation](https://www.postgresql.org/docs/18/using-explain.html). The catalogue also exercises the documented [window functions](https://www.postgresql.org/docs/18/functions-window.html) and [recursive WITH queries](https://www.postgresql.org/docs/18/queries-with.html).
