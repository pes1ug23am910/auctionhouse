with expected as (
    select event_id from {{ source('auctionhouse_raw', 'cut_members') }}
    where cut_id=cast('{{ var("cut_id", "00000000-0000-0000-0000-000000000000") }}' as uuid)
), observed as (
    select event_id from {{ ref('fct_auction_events') }}
)
select 'absent_at_cut' as discrepancy, e.event_id
from expected e left join observed o using(event_id) where o.event_id is null
union all
select 'unexpected_attributed_attempt', a.event_id
from {{ source('auctionhouse_raw', 'load_attempts') }} a
left join expected e using(event_id)
where a.cut_id=cast('{{ var("cut_id", "00000000-0000-0000-0000-000000000000") }}' as uuid)
  and a.event_id is not null and e.event_id is null
