CREATE TABLE IF NOT EXISTS config_overrides (
    "key"       VARCHAR(255) NOT NULL PRIMARY KEY,
    "value"     VARCHAR(4096) NOT NULL,
    last_edit   TIMESTAMP NOT NULL
);
