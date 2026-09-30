SELECT a.auction_id, a.opening_minor
FROM __SCHEMA__.auctions a
WHERE a.seller_id = 2
  AND NOT EXISTS (
      SELECT 1 FROM __SCHEMA__.bids b WHERE b.auction_id = a.auction_id
  )
ORDER BY a.auction_id
