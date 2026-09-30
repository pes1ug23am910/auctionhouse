select
    auction_id::varchar || ':' || aggregate_version::varchar as auction_version_key,
    auction_id,
    aggregate_version,
    owner_id,
    title,
    description,
    auction_status,
    opening_price_minor,
    minimum_increment_minor,
    ends_at,
    auction_created_at,
    occurred_at as observed_valid_from,
    lead(occurred_at) over (partition by auction_id order by aggregate_version) as next_observed_at,
    row_number() over (partition by auction_id order by aggregate_version desc) = 1 as is_latest_observed
from {{ ref('stg_events') }}
