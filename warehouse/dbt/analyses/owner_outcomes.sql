select owner_id, count(*) as closed_auctions,
       count(winner_id) as auctions_with_winner,
       sum(final_offer_amount_minor) as sum_of_final_offers_minor
from {{ ref('mart_auction_outcomes') }}
group by owner_id
order by closed_auctions desc, owner_id
