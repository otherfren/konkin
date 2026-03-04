-- Add mandatory reason column to approval_requests.
-- Stores the agent-provided reason WHY the tool action was requested (distinct from state_reason_code/state_reason_text which track state transitions).
ALTER TABLE approval_requests ADD COLUMN reason CLOB DEFAULT '' NOT NULL;
