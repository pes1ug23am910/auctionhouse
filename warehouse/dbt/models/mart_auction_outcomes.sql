select
    e.auction_id,
    e.event_id as closure_event_id,
    e.owner_id,
    e.winner_id,
    e.occurred_at as closed_at,
    e.final_offer_amount_minor,
    a.opening_price_minor,
    e.final_offer_amount_minor - a.opening_price_minor as offer_above_opening_minor,
    e.winner_id is not null as has_winner
from {{ ref('fct_auction_events') }} e
join {{ ref('dim_auction_versions') }} a using (auction_version_key)
where e.event_type = 'auction.closed'
