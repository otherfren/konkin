-- Add QUEUED_FOR_EXECUTION state for spendable balance gating.
-- H2 does not support ALTER CONSTRAINT, so we drop and re-create the CHECK constraints.

ALTER TABLE approval_requests DROP CONSTRAINT IF EXISTS chk_approval_requests_state;
ALTER TABLE approval_requests ADD CONSTRAINT chk_approval_requests_state CHECK (
    state IN (
        'QUEUED',
        'PENDING',
        'APPROVED',
        'DENIED',
        'TIMED_OUT',
        'CANCELLED',
        'QUEUED_FOR_EXECUTION',
        'EXECUTING',
        'COMPLETED',
        'FAILED',
        'REJECTED',
        'EXPIRED'
    )
);

ALTER TABLE approval_state_transitions DROP CONSTRAINT IF EXISTS chk_approval_state_transitions_to_state;
ALTER TABLE approval_state_transitions ADD CONSTRAINT chk_approval_state_transitions_to_state CHECK (
    to_state IN (
        'QUEUED',
        'PENDING',
        'APPROVED',
        'DENIED',
        'TIMED_OUT',
        'CANCELLED',
        'QUEUED_FOR_EXECUTION',
        'EXECUTING',
        'COMPLETED',
        'FAILED',
        'REJECTED',
        'EXPIRED'
    )
);
