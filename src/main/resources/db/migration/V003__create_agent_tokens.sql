CREATE TABLE IF NOT EXISTS agent_tokens (
    token       VARCHAR(128)  NOT NULL PRIMARY KEY,
    agent_name  VARCHAR(255)  NOT NULL,
    created_at  TIMESTAMP     NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_agent_tokens_agent ON agent_tokens (agent_name);
