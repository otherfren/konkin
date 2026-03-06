-- [L-1] Rename token column to token_hash and clear existing plaintext tokens.
-- Existing plaintext tokens are invalidated (agents will re-authenticate).

DELETE FROM agent_tokens;

ALTER TABLE agent_tokens ALTER COLUMN token RENAME TO token_hash;
