select closure_event_id
from {{ ref('mart_auction_outcomes') }}
where (has_winner and (winner_id is null or final_offer_amount_minor is null))
   or (not has_winner and (winner_id is not null or final_offer_amount_minor is not null))
