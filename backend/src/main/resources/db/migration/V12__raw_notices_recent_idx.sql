-- Incremental BZP runs look up the newest raw notice per type among recently fetched ones.
CREATE INDEX raw_notices_source_fetched_idx ON raw_notices (data_source_id, fetched_at DESC);
