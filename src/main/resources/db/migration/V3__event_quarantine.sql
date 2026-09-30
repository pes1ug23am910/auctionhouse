CREATE TABLE event_quarantine (
    topic text NOT NULL,
    partition_id integer NOT NULL,
    record_offset bigint NOT NULL,
    reason varchar(80) NOT NULL,
    payload text,
    payload_truncated boolean NOT NULL,
    first_seen_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(topic, partition_id, record_offset)
);
