SELECT bid_id, auction_id, amount_minor
FROM __SCHEMA__.bids
WHERE auction_id = 1
  AND (placed_at, bid_id) > (timestamptz '2026-01-01 00:00:15+00', 4::bigint)
ORDER BY placed_at, bid_id
LIMIT 50
