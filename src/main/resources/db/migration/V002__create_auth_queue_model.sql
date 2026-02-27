CREATE TABLE IF NOT EXISTS approval_requests (
    id                           VARCHAR(64) PRIMARY KEY,
    coin                         VARCHAR(64) NOT NULL,
    tool_name                    VARCHAR(128) NOT NULL,
    request_session_id           VARCHAR(255),
    nonce_uuid                   VARCHAR(64) NOT NULL,
    payload_hash_sha256          VARCHAR(128) NOT NULL,
    nonce_composite              VARCHAR(256) NOT NULL,
    to_address                   VARCHAR(512),
    amount_native                VARCHAR(128),
    fee_policy                   VARCHAR(64),
    fee_cap_native               VARCHAR(128),
    memo                         CLOB,
    requested_at                 TIMESTAMP NOT NULL,
    expires_at                   TIMESTAMP NOT NULL,
    state                        VARCHAR(32) NOT NULL,
    state_reason_code            VARCHAR(128),
    state_reason_text            CLOB,
    min_approvals_required       INTEGER NOT NULL,
    approvals_granted            INTEGER NOT NULL DEFAULT 0,
    approvals_denied             INTEGER NOT NULL DEFAULT 0,
    policy_action_at_creation    VARCHAR(32),
    created_at                   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at                  TIMESTAMP,

    CONSTRAINT uq_approval_requests_nonce_composite UNIQUE (nonce_composite),
    CONSTRAINT chk_approval_requests_min_approvals CHECK (min_approvals_required >= 1),
    CONSTRAINT chk_approval_requests_approvals_granted CHECK (approvals_granted >= 0),
    CONSTRAINT chk_approval_requests_approvals_denied CHECK (approvals_denied >= 0),
    CONSTRAINT chk_approval_requests_state CHECK (
        state IN (
            'QUEUED',
            'PENDING',
            'APPROVED',
            'DENIED',
            'TIMED_OUT',
            'CANCELLED',
            'EXECUTING',
            'COMPLETED',
            'FAILED',
            'REJECTED',
            'EXPIRED'
        )
    )
);

CREATE TABLE IF NOT EXISTS approval_state_transitions (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id      VARCHAR(64) NOT NULL,
    from_state      VARCHAR(32),
    to_state        VARCHAR(32) NOT NULL,
    actor_type      VARCHAR(32) NOT NULL,
    actor_id        VARCHAR(255),
    reason_code     VARCHAR(128),
    reason_text     CLOB,
    metadata_json   CLOB,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_approval_state_transitions_request
        FOREIGN KEY (request_id) REFERENCES approval_requests(id),
    CONSTRAINT chk_approval_state_transitions_to_state CHECK (
        to_state IN (
            'QUEUED',
            'PENDING',
            'APPROVED',
            'DENIED',
            'TIMED_OUT',
            'CANCELLED',
            'EXECUTING',
            'COMPLETED',
            'FAILED',
            'REJECTED',
            'EXPIRED'
        )
    )
);

CREATE TABLE IF NOT EXISTS approval_channels (
    id                  VARCHAR(128) PRIMARY KEY,
    channel_type        VARCHAR(64) NOT NULL,
    display_name        VARCHAR(255),
    enabled             BOOLEAN NOT NULL,
    config_fingerprint  VARCHAR(128),
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS approval_request_channels (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id      VARCHAR(64) NOT NULL,
    channel_id      VARCHAR(128) NOT NULL,
    delivery_state  VARCHAR(32) NOT NULL,
    first_sent_at   TIMESTAMP,
    last_attempt_at TIMESTAMP,
    attempt_count   INTEGER NOT NULL DEFAULT 0,
    last_error      CLOB,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_approval_request_channels_request
        FOREIGN KEY (request_id) REFERENCES approval_requests(id),
    CONSTRAINT fk_approval_request_channels_channel
        FOREIGN KEY (channel_id) REFERENCES approval_channels(id),
    CONSTRAINT uq_approval_request_channels_request_channel UNIQUE (request_id, channel_id),
    CONSTRAINT chk_approval_request_channels_delivery_state CHECK (
        delivery_state IN ('queued', 'sent', 'failed', 'skipped', 'acked')
    ),
    CONSTRAINT chk_approval_request_channels_attempt_count CHECK (attempt_count >= 0)
);

CREATE TABLE IF NOT EXISTS approval_votes (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id      VARCHAR(64) NOT NULL,
    channel_id      VARCHAR(128) NOT NULL,
    decision        VARCHAR(16) NOT NULL,
    decision_reason CLOB,
    decided_by      VARCHAR(255),
    decided_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_approval_votes_request
        FOREIGN KEY (request_id) REFERENCES approval_requests(id),
    CONSTRAINT fk_approval_votes_channel
        FOREIGN KEY (channel_id) REFERENCES approval_channels(id),
    CONSTRAINT uq_approval_votes_request_channel UNIQUE (request_id, channel_id),
    CONSTRAINT chk_approval_votes_decision CHECK (decision IN ('approve', 'deny'))
);

CREATE TABLE IF NOT EXISTS approval_execution_attempts (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id          VARCHAR(64) NOT NULL,
    attempt_no          INTEGER NOT NULL,
    started_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at         TIMESTAMP,
    result              VARCHAR(64) NOT NULL,
    error_class         VARCHAR(255),
    error_message       CLOB,
    txid                VARCHAR(255),
    daemon_fee_native   VARCHAR(128),

    CONSTRAINT fk_approval_execution_attempts_request
        FOREIGN KEY (request_id) REFERENCES approval_requests(id),
    CONSTRAINT uq_approval_execution_attempts_request_attempt UNIQUE (request_id, attempt_no),
    CONSTRAINT chk_approval_execution_attempts_attempt_no CHECK (attempt_no >= 1),
    CONSTRAINT chk_approval_execution_attempts_result CHECK (
        result IN ('success', 'transient_error', 'non_retryable_error', 'fee_drift_requires_reapproval')
    )
);

CREATE TABLE IF NOT EXISTS approval_coin_runtime (
    coin               VARCHAR(64) PRIMARY KEY,
    active_request_id  VARCHAR(64),
    cooldown_until     TIMESTAMP,
    lockdown_until     TIMESTAMP,
    updated_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_approval_coin_runtime_active_request
        FOREIGN KEY (active_request_id) REFERENCES approval_requests(id)
);

CREATE INDEX IF NOT EXISTS idx_approval_requests_coin_state_requested_at
    ON approval_requests (coin, state, requested_at);

CREATE INDEX IF NOT EXISTS idx_approval_requests_state_expires_at
    ON approval_requests (state, expires_at);

CREATE INDEX IF NOT EXISTS idx_approval_requests_session_requested_at
    ON approval_requests (request_session_id, requested_at);

CREATE INDEX IF NOT EXISTS idx_approval_state_transitions_request_created_at
    ON approval_state_transitions (request_id, created_at);

CREATE INDEX IF NOT EXISTS idx_approval_votes_request_decided_at
    ON approval_votes (request_id, decided_at);

CREATE INDEX IF NOT EXISTS idx_approval_request_channels_request_delivery_state
    ON approval_request_channels (request_id, delivery_state);

CREATE INDEX IF NOT EXISTS idx_approval_execution_attempts_request_attempt
    ON approval_execution_attempts (request_id, attempt_no);
