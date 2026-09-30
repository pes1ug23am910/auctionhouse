ALTER TABLE outbox_events
    ADD COLUMN trace_parent varchar(55),
    ADD COLUMN trace_state varchar(512);
ALTER TABLE outbox_events
    ADD CONSTRAINT outbox_trace_parent_shape CHECK (
        trace_parent IS NULL OR trace_parent ~ '^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$');
