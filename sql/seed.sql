INSERT INTO __SCHEMA__.actors SELECT generate_series(1, 2100);
INSERT INTO __SCHEMA__.categories
SELECT id, CASE WHEN id = 1 THEN NULL ELSE id / 2 END, 'Category ' || id
FROM generate_series(1, 31) AS ids(id);
INSERT INTO __SCHEMA__.auctions
SELECT id, 1 + (id - 1) % 100, 2 + (id * 17 + __SEED__) % 30,
       100000 + id * 100
FROM generate_series(1, 12000) AS ids(id);
WITH identities AS (
    SELECT n,
           CASE WHEN n <= __ROWS__ / 2
                THEN 1 + (n - 1) % 100
                ELSE 101 + (n - __ROWS__ / 2 - 1) % 9900 END AS auction_id,
           CASE WHEN n <= __ROWS__ / 2
                THEN 1 + (n - 1) / 100
                ELSE 1 + (n - __ROWS__ / 2 - 1) / 9900 END AS ordinal
    FROM generate_series(1::bigint, __ROWS__::bigint) AS ids(n)
)
INSERT INTO __SCHEMA__.bids
SELECT n, auction_id, 101 + (n * 7919 + __SEED__) % 2000,
       100000 + auction_id * 100 + ordinal * 10,
       timestamptz '2026-01-01 00:00:00+00' + n * interval '1 second'
FROM identities;
