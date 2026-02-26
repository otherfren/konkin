-- Generic key/value store with last_edit tracking
CREATE TABLE IF NOT EXISTS kv_store (
    "key"       VARCHAR(255)  NOT NULL PRIMARY KEY,
    "value"     CLOB          NOT NULL DEFAULT '',
    last_edit   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Index on last_edit for querying recent changes
CREATE INDEX IF NOT EXISTS idx_kv_store_last_edit ON kv_store (last_edit);
