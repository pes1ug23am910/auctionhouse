select event_id,
       count(*) as attempts,
       count(*) filter (where outcome='accepted') as accepted_effects,
       count(*) filter (where outcome='duplicate') as duplicate_arrivals,
       count(*) filter (where outcome='quarantined') as quarantined_arrivals
from {{ source('auctionhouse_raw', 'load_attempts') }}
group by event_id
having count(*)>1
order by attempts desc, event_id
