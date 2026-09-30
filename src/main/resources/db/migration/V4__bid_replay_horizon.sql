ALTER TABLE bid_intents ADD COLUMN replay_until timestamptz;
UPDATE bid_intents SET replay_until=decided_at+interval '30 days';
ALTER TABLE bid_intents ALTER COLUMN replay_until SET NOT NULL;
-- Identity and fingerprint remain permanently reserved after the replay window ends.
