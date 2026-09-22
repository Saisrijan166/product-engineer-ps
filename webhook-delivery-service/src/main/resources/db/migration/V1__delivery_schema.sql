-- Webhook delivery service schema.
--
-- This database is both the store of record and the work queue. The indexes below are not an
-- optimisation added afterwards: the scheduler's claim query and the reaper's sweep run on every
-- tick of every instance, and both are written to hit a partial index that stays small no matter
-- how much completed history accumulates.

CREATE TABLE delivery (
    id                UUID         PRIMARY KEY,

    -- The caller's identifier, carried through from the ingested event. UNIQUE is the whole of
    -- consumer-side idempotency: a dispatch delivered twice cannot create a second delivery job.
    event_id          VARCHAR(128) NOT NULL,
    event_type        VARCHAR(128),
    occurred_at       TIMESTAMPTZ,
    payload           JSONB,

    -- Snapshotted at creation rather than read from configuration at attempt time, so the history
    -- stays truthful after either one is changed.
    target_url        TEXT         NOT NULL,
    max_attempts      INTEGER      NOT NULL,

    status            VARCHAR(24)  NOT NULL,
    attempt_count     INTEGER      NOT NULL DEFAULT 0,

    -- The scheduling column. Non-null exactly while the delivery is waiting to be claimed.
    next_attempt_at   TIMESTAMPTZ,

    -- Held only while IN_FLIGHT. An expired lease is how a crash becomes visible.
    lease_owner       VARCHAR(64),
    lease_expires_at  TIMESTAMPTZ,

    terminal_reason   VARCHAR(32),
    last_outcome      VARCHAR(24),

    created_at        TIMESTAMPTZ  NOT NULL,
    first_attempt_at  TIMESTAMPTZ,
    completed_at      TIMESTAMPTZ,

    version           BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT uq_delivery_event_id UNIQUE (event_id),

    -- The enums are spelled out so a bad write fails at the database rather than becoming a row
    -- nothing can interpret. Kept in step with com.caygnus.webhook.common.model.
    CONSTRAINT ck_delivery_status CHECK (status IN (
        'PENDING', 'IN_FLIGHT', 'RETRY_SCHEDULED', 'SUCCEEDED', 'FAILED_PERMANENT', 'FAILED_EXHAUSTED')),
    CONSTRAINT ck_delivery_terminal_reason CHECK (terminal_reason IS NULL OR terminal_reason IN (
        'NON_RETRYABLE_RESPONSE', 'ATTEMPTS_EXHAUSTED')),
    CONSTRAINT ck_delivery_last_outcome CHECK (last_outcome IS NULL OR last_outcome IN (
        'SUCCESS', 'RETRYABLE_FAILURE', 'PERMANENT_FAILURE')),

    -- The attempt bound, restated where nothing can talk its way past it.
    CONSTRAINT ck_delivery_max_attempts CHECK (max_attempts >= 1),
    CONSTRAINT ck_delivery_attempt_count CHECK (attempt_count >= 0 AND attempt_count <= max_attempts),

    -- A terminal delivery is never due for anything.
    CONSTRAINT ck_delivery_terminal_not_scheduled CHECK (
        status NOT IN ('SUCCEEDED', 'FAILED_PERMANENT', 'FAILED_EXHAUSTED') OR next_attempt_at IS NULL),

    -- A lease belongs to an in-flight delivery and to no other.
    CONSTRAINT ck_delivery_lease_only_in_flight CHECK (
        (status = 'IN_FLIGHT' AND lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL)
        OR (status <> 'IN_FLIGHT' AND lease_owner IS NULL AND lease_expires_at IS NULL))
);

-- The hot path: every scheduler tick on every instance. Partial, so it indexes only work that is
-- still live -- completed deliveries accumulate forever and must not slow the queue down.
CREATE INDEX idx_delivery_due
    ON delivery (next_attempt_at)
    WHERE status IN ('PENDING', 'RETRY_SCHEDULED');

-- The reaper's sweep. Also partial: only in-flight deliveries can have a lease to expire.
CREATE INDEX idx_delivery_lease_expiry
    ON delivery (lease_expires_at)
    WHERE status = 'IN_FLIGHT';

-- Listing and the dead-letter view (GET /internal/v1/deliveries?status=FAILED_EXHAUSTED).
CREATE INDEX idx_delivery_status_created
    ON delivery (status, created_at DESC);


-- Append-only. Rows are written once when an attempt completes and never updated afterwards;
-- that is enforced in the domain, which exposes no way to mutate a persisted attempt.
CREATE TABLE delivery_attempt (
    id                     UUID        PRIMARY KEY,
    delivery_id            UUID        NOT NULL REFERENCES delivery (id) ON DELETE CASCADE,

    attempt_number         INTEGER     NOT NULL,

    started_at             TIMESTAMPTZ NOT NULL,
    completed_at           TIMESTAMPTZ,
    duration_ms            INTEGER,

    outcome                VARCHAR(24) NOT NULL,
    http_status            INTEGER,

    -- Null whenever the receiver did answer, however unhelpfully.
    error_class            VARCHAR(32),
    error_message          TEXT,

    -- Truncated by the domain before it gets here; bounded storage is the point.
    response_body_snippet  TEXT,

    retry_after_seconds    INTEGER,

    -- What this attempt scheduled, so the backoff is auditable after the fact.
    next_attempt_at        TIMESTAMPTZ,

    -- Which instance ran it. Makes a multi-instance history readable.
    worker_id              VARCHAR(64) NOT NULL,

    -- A double attempt is impossible by construction, not by careful coding.
    CONSTRAINT uq_delivery_attempt_number UNIQUE (delivery_id, attempt_number),

    CONSTRAINT ck_attempt_number CHECK (attempt_number >= 1),
    CONSTRAINT ck_attempt_outcome CHECK (outcome IN (
        'SUCCESS', 'RETRYABLE_FAILURE', 'PERMANENT_FAILURE')),
    CONSTRAINT ck_attempt_error_class CHECK (error_class IS NULL OR error_class IN (
        'CONNECT_TIMEOUT', 'READ_TIMEOUT', 'CONNECTION_REFUSED', 'DNS_FAILURE',
        'TLS_ERROR', 'MALFORMED_URL', 'CIRCUIT_OPEN', 'UNKNOWN')),
    CONSTRAINT ck_attempt_http_status CHECK (http_status IS NULL OR http_status BETWEEN 100 AND 599)
);
