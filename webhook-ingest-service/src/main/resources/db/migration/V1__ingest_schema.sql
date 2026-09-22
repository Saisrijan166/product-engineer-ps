-- Webhook ingest service schema.
--
-- Two tables and two unique constraints, and between them they are the whole of AC4. Neither
-- constraint is a safety net behind application logic -- there is no application logic to back up.
-- Concurrency is arbitrated by PostgreSQL, and these are how it arbitrates.

CREATE TABLE webhook_event (
    id                          UUID         PRIMARY KEY,

    -- The caller's own identifier. UNIQUE is the idempotency invariant: whatever else happens,
    -- one submitted eventId means one accepted event.
    event_id                    VARCHAR(128) NOT NULL,

    type                        VARCHAR(128) NOT NULL,
    occurred_at                 TIMESTAMPTZ,
    payload                     JSONB        NOT NULL,

    -- SHA-256 of the canonicalised payload. Lets a resubmission be told apart from a *different*
    -- event wearing the same id, which is a caller bug worth reporting rather than absorbing.
    payload_hash                VARCHAR(64)  NOT NULL,

    received_at                 TIMESTAMPTZ  NOT NULL,

    -- Observability for AC4: how often this event has been submitted again, and when last.
    duplicate_submission_count  INTEGER      NOT NULL DEFAULT 0,
    last_duplicate_at           TIMESTAMPTZ,

    CONSTRAINT uq_webhook_event_event_id UNIQUE (event_id),
    CONSTRAINT ck_webhook_event_duplicate_count CHECK (duplicate_submission_count >= 0),

    -- VARCHAR rather than CHAR: PostgreSQL's char(n) is blank-padded, so a short value would be
    -- silently padded to length instead of rejected. This says what the column actually holds.
    CONSTRAINT ck_webhook_event_payload_hash CHECK (payload_hash ~ '^[0-9a-f]{64}$')
);

-- The demo listing, newest first.
CREATE INDEX idx_webhook_event_received_at ON webhook_event (received_at DESC);


-- The durable intent to schedule a delivery.
--
-- Written in the same transaction as the event, which is the whole point: there is no moment at
-- which an event has been accepted but the decision to deliver it exists only in memory. If this
-- row is here, the delivery will be scheduled, however many times the process dies first.
CREATE TABLE outbox_dispatch (
    id                BIGSERIAL    PRIMARY KEY,

    -- UNIQUE here is the structural form of "does not schedule a second independent delivery
    -- job" -- one dispatch intent per event, enforced rather than intended.
    event_id          VARCHAR(128) NOT NULL,

    -- The serialised CreateDeliveryRequest, so the dispatcher needs nothing but this row.
    payload           JSONB        NOT NULL,

    status            VARCHAR(16)  NOT NULL,

    -- The dispatcher's own bounded retry, separate from the delivery attempt budget.
    attempts          INTEGER      NOT NULL DEFAULT 0,
    next_dispatch_at  TIMESTAMPTZ  NOT NULL,

    -- Filled in once the delivery service acknowledges; closes the loop for the composed read.
    delivery_id       UUID,
    last_error        TEXT,

    created_at        TIMESTAMPTZ  NOT NULL,
    dispatched_at     TIMESTAMPTZ,

    CONSTRAINT uq_outbox_dispatch_event_id UNIQUE (event_id),
    CONSTRAINT ck_outbox_dispatch_status CHECK (status IN ('PENDING', 'DISPATCHED', 'FAILED')),
    CONSTRAINT ck_outbox_dispatch_attempts CHECK (attempts >= 0),

    -- A dispatched row has something to show for it.
    CONSTRAINT ck_outbox_dispatch_completed CHECK (
        status <> 'DISPATCHED' OR (delivery_id IS NOT NULL AND dispatched_at IS NOT NULL))
);

-- The dispatcher's claim query. Partial, so it indexes only work still waiting to go out;
-- dispatched rows accumulate forever and must not slow the sweep down.
CREATE INDEX idx_outbox_dispatch_pending
    ON outbox_dispatch (next_dispatch_at)
    WHERE status = 'PENDING';
