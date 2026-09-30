with missing as (
    select event_id from {{ source('auctionhouse_raw', 'events') }}
    except select event_id from {{ ref('fct_auction_events') }}
), unexpected as (
    select event_id from {{ ref('fct_auction_events') }}
    except select event_id from {{ source('auctionhouse_raw', 'events') }}
)
select * from missing
union all
select * from unexpected
