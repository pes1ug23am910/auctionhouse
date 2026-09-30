SELECT b.bid_id, b.auction_id, b.amount_minor,
       b.amount_minor - lag(b.amount_minor, 1, a.opening_minor)
           OVER (PARTITION BY b.auction_id ORDER BY b.placed_at, b.bid_id) AS increment_minor
FROM __SCHEMA__.bids b
JOIN __SCHEMA__.auctions a USING (auction_id)
WHERE b.auction_id = 1
ORDER BY b.placed_at, b.bid_id
