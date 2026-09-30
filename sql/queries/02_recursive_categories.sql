WITH RECURSIVE subtree AS (
    SELECT category_id, 0 AS depth FROM __SCHEMA__.categories WHERE category_id = 2
    UNION ALL
    SELECT c.category_id, s.depth + 1
    FROM __SCHEMA__.categories c JOIN subtree s ON c.parent_id = s.category_id
)
SELECT s.category_id, s.depth, count(DISTINCT a.auction_id) AS auctions,
       count(b.bid_id) AS accepted_bids
FROM subtree s
LEFT JOIN __SCHEMA__.auctions a USING (category_id)
LEFT JOIN __SCHEMA__.bids b USING (auction_id)
GROUP BY s.category_id, s.depth
ORDER BY s.category_id
