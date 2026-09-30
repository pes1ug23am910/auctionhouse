CREATE INDEX bids_auction_order ON __SCHEMA__.bids (auction_id, placed_at, bid_id)
    INCLUDE (amount_minor, bidder_id);
CREATE INDEX auctions_seller_order ON __SCHEMA__.auctions (seller_id, auction_id)
    INCLUDE (opening_minor, category_id);
CREATE INDEX auctions_category ON __SCHEMA__.auctions (category_id, auction_id);
CREATE INDEX bids_bidder_time ON __SCHEMA__.bids (bidder_id, placed_at);
