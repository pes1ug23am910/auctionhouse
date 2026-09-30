WITH first_seen AS (
    SELECT bidder_id, min((placed_at AT TIME ZONE 'UTC')::date) AS cohort_day
    FROM __SCHEMA__.bids GROUP BY bidder_id
), cohort_sizes AS (
    SELECT cohort_day, count(*) AS cohort_bidders FROM first_seen GROUP BY cohort_day
), activity AS (
    SELECT f.cohort_day,
           (b.placed_at AT TIME ZONE 'UTC')::date - f.cohort_day AS day_offset,
           count(DISTINCT b.bidder_id) AS active_bidders
    FROM __SCHEMA__.bids b JOIN first_seen f USING (bidder_id)
    GROUP BY f.cohort_day, day_offset
)
SELECT a.cohort_day::text AS cohort_day, a.day_offset, a.active_bidders, s.cohort_bidders,
       round(a.active_bidders::numeric / s.cohort_bidders, 4) AS retention_fraction
FROM activity a JOIN cohort_sizes s USING (cohort_day)
ORDER BY a.cohort_day, a.day_offset
