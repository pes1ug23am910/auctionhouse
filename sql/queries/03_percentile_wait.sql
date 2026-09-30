WITH gaps AS (
    SELECT extract(epoch FROM
        lead(placed_at) OVER (PARTITION BY auction_id ORDER BY placed_at, bid_id) - placed_at
    ) AS seconds_to_next_bid
    FROM __SCHEMA__.bids
)
SELECT count(seconds_to_next_bid) AS observed_gaps,
       round((percentile_cont(0.5) WITHIN GROUP (ORDER BY seconds_to_next_bid))::numeric, 2) AS p50_seconds,
       round((percentile_cont(0.95) WITHIN GROUP (ORDER BY seconds_to_next_bid))::numeric, 2) AS p95_seconds
FROM gaps
