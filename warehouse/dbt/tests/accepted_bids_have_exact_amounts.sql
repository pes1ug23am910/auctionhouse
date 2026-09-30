select event_id
from {{ ref('fct_auction_events') }}
where (is_accepted_bid and (accepted_bid_amount_minor is null or accepted_bid_amount_minor <= 0))
   or (not is_accepted_bid and accepted_bid_amount_minor is not null)
