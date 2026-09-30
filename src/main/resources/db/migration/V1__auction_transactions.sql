CREATE TABLE accounts (
    id uuid PRIMARY KEY,
    issuer text NOT NULL,
    subject text NOT NULL,
    display_name varchar(120) NOT NULL,
    role varchar(16) NOT NULL DEFAULT 'USER' CHECK (role IN ('USER', 'ADMIN')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (issuer, subject)
);

CREATE TABLE categories (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    parent_id bigint REFERENCES categories(id),
    name varchar(80) NOT NULL,
    CHECK (parent_id IS NULL OR parent_id <> id)
);

CREATE TABLE auctions (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES accounts(id),
    title varchar(160) NOT NULL CHECK (length(trim(title)) > 0),
    description varchar(4000) NOT NULL DEFAULT '',
    category_id bigint REFERENCES categories(id),
    opening_price bigint NOT NULL CHECK (opening_price BETWEEN 1 AND 9000000000000000),
    minimum_increment bigint NOT NULL CHECK (minimum_increment BETWEEN 1 AND 9000000000000000),
    ends_at timestamptz NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('DRAFT','OPEN','CLOSED','CANCELLED')),
    highest_bid_amount bigint CHECK (highest_bid_amount BETWEEN opening_price AND 9000000000000000),
    highest_bidder_id uuid REFERENCES accounts(id),
    version bigint NOT NULL CHECK (version > 0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK ((highest_bid_amount IS NULL) = (highest_bidder_id IS NULL)),
    CHECK (highest_bidder_id IS NULL OR highest_bidder_id <> owner_id),
    CHECK (status NOT IN ('DRAFT','CANCELLED') OR highest_bid_amount IS NULL)
);
CREATE INDEX auctions_browse ON auctions (status, created_at DESC, id);
CREATE INDEX auctions_closure ON auctions (ends_at, id) WHERE status='OPEN';

CREATE TABLE bids (
    id uuid PRIMARY KEY,
    auction_id uuid NOT NULL REFERENCES auctions(id),
    actor_id uuid NOT NULL REFERENCES accounts(id),
    amount bigint NOT NULL CHECK (amount BETWEEN 1 AND 9000000000000000),
    auction_version bigint NOT NULL,
    accepted_at timestamptz NOT NULL,
    UNIQUE (auction_id, auction_version)
);
CREATE INDEX bids_auction_history ON bids (auction_id, auction_version DESC);
CREATE INDEX bids_actor ON bids (actor_id, accepted_at DESC);

CREATE TABLE bid_intents (
    actor_id uuid NOT NULL REFERENCES accounts(id),
    auction_id uuid NOT NULL REFERENCES auctions(id),
    operation varchar(24) NOT NULL CHECK (operation='bid'),
    intent_key varchar(128) NOT NULL,
    fingerprint char(64) NOT NULL,
    amount bigint NOT NULL,
    accepted boolean NOT NULL,
    rejection varchar(40),
    bid_id uuid REFERENCES bids(id),
    auction_version bigint NOT NULL,
    decided_at timestamptz NOT NULL,
    PRIMARY KEY (actor_id, auction_id, operation, intent_key),
    CHECK ((accepted AND bid_id IS NOT NULL AND rejection IS NULL) OR
           (NOT accepted AND bid_id IS NULL AND rejection IS NOT NULL))
);

CREATE TABLE listing_reservations (
    auction_id uuid PRIMARY KEY REFERENCES auctions(id),
    reserved_at timestamptz NOT NULL
);
CREATE TABLE announcements (
    auction_id uuid PRIMARY KEY REFERENCES auctions(id),
    state varchar(16) NOT NULL CHECK (state IN ('INACTIVE','ACTIVE')),
    activated_at timestamptz
);

CREATE TABLE outbox_events (
    event_id uuid PRIMARY KEY,
    event_type varchar(60) NOT NULL,
    schema_version integer NOT NULL CHECK (schema_version=1),
    aggregate_id uuid NOT NULL REFERENCES auctions(id),
    aggregate_version bigint NOT NULL,
    occurred_at timestamptz NOT NULL,
    payload jsonb NOT NULL,
    attempts integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    published_at timestamptz,
    broker_partition integer,
    broker_offset bigint,
    UNIQUE (aggregate_id, aggregate_version)
);
CREATE INDEX outbox_pending ON outbox_events (next_attempt_at, occurred_at) WHERE published_at IS NULL;
CREATE TABLE notification_effects (
    event_id uuid PRIMARY KEY,
    event_type varchar(60) NOT NULL,
    payload jsonb NOT NULL,
    applied_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE notification_deliveries (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id uuid NOT NULL,
    duplicate boolean NOT NULL,
    received_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE event_cuts (
    cut_id uuid PRIMARY KEY,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE event_cut_members (
    cut_id uuid NOT NULL REFERENCES event_cuts(cut_id),
    event_id uuid NOT NULL REFERENCES outbox_events(event_id),
    PRIMARY KEY (cut_id, event_id)
);
