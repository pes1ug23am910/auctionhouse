SELECT a.seller_id, count(DISTINCT a.auction_id) AS auctions,
       count(b.bid_id) AS accepted_bids,
       coalesce(sum(b.amount_minor), 0) AS bid_volume_minor,
       count(DISTINCT b.bidder_id) AS active_bidders
FROM __SCHEMA__.auctions a
LEFT JOIN __SCHEMA__.bids b USING (auction_id)
GROUP BY a.seller_id
ORDER BY a.seller_id
