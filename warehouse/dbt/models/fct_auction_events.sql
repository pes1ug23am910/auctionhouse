select
    event_id,
    auction_id::varchar || ':' || aggregate_version::varchar as auction_version_key,
    auction_id,
    aggregate_version,
    event_type,
    event_date,
    occurred_at,
    owner_id,
    leading_bidder_id,
    winner_id,
    event_type = 'bid.accepted' as is_accepted_bid,
    case when event_type = 'bid.accepted' then highest_bid_amount_minor end as accepted_bid_amount_minor,
    case when event_type = 'auction.closed' then highest_bid_amount_minor end as final_offer_amount_minor,
    envelope_sha256
from {{ ref('stg_events') }}
