select
    event_date,
    count(*) as business_events,
    count(*) filter (where event_type = 'auction.created') as auctions_created,
    count(*) filter (where event_type = 'auction.published') as auctions_published,
    count(*) filter (where is_accepted_bid) as accepted_bids,
    sum(accepted_bid_amount_minor) as accepted_offer_volume_minor,
    count(distinct leading_bidder_id) filter (where is_accepted_bid) as active_bidders
from {{ ref('fct_auction_events') }}
group by event_date
