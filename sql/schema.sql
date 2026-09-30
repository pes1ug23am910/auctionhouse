CREATE SCHEMA __SCHEMA__;
CREATE TABLE __SCHEMA__.actors (
    actor_id bigint PRIMARY KEY
);
CREATE TABLE __SCHEMA__.categories (
    category_id integer PRIMARY KEY,
    parent_id integer REFERENCES __SCHEMA__.categories(category_id),
    name text NOT NULL
);
CREATE TABLE __SCHEMA__.auctions (
    auction_id bigint PRIMARY KEY,
    seller_id bigint NOT NULL REFERENCES __SCHEMA__.actors(actor_id),
    category_id integer NOT NULL REFERENCES __SCHEMA__.categories(category_id),
    opening_minor bigint NOT NULL CHECK (opening_minor > 0)
);
CREATE TABLE __SCHEMA__.bids (
    bid_id bigint PRIMARY KEY,
    auction_id bigint NOT NULL REFERENCES __SCHEMA__.auctions(auction_id),
    bidder_id bigint NOT NULL REFERENCES __SCHEMA__.actors(actor_id),
    amount_minor bigint NOT NULL CHECK (amount_minor > 0),
    placed_at timestamptz NOT NULL
);
