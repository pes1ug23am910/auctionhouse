with appearances as (
    select owner_id as actor_id, true as appears_as_owner, false as appears_as_bidder from {{ ref('stg_events') }}
    union all
    select leading_bidder_id, false, true from {{ ref('stg_events') }} where leading_bidder_id is not null
)
select actor_id, bool_or(appears_as_owner) as appears_as_owner,
       bool_or(appears_as_bidder) as appears_as_bidder
from appearances
group by actor_id
