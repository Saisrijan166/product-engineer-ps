# Webhook Retry Engine — Architecture & Implementation Plan (v2)

**Problem:** Caygnus Product Engineering Challenge, Problem 2 (`02-webhook-retry-engine.md`)

**Stack:** Java 21 · Spring Boot 3.x · Spring Data JPA / Hibernate · PostgreSQL · Spring RestClient · Docker · Maven · JUnit 5 · Mockito · Testcontainers

**Status:** design approved, not yet implemented. Implementation proceeds step-by-step per §18.

---

## 0. What Changed From v1, and Why It Is Better

v1 used Kafka, Redis and Kubernetes. All three were removed. This table records *why*, so the decisions are not relitigated.

| v1 (removed) | v2 (replacement) | Why v2 is better *for this brief* |
|---|---|---|
| Kafka delay topics for retry scheduling | `delivery.next_attempt_at` + a polling claimer using `FOR UPDATE SKIP LOCKED` | Retry timing becomes a **timestamp comparison**, so backoff is arbitrary and exact instead of bucketed into tiers. One scheduler, not two. |
| Kafka as ingest→delivery transport | **Transactional outbox + `RestClient` dispatch** with an idempotency key | Same zero-loss property, one fewer moving part. The outbox is what actually provided the guarantee; Kafka was never the durable part. |
| Kafka DLQ topic | Terminal DB states + `GET /deliveries?status=FAILED_EXHAUSTED` | The dead-letter view is queryable and joinable with its own attempt history. |
| Redis `SET NX` ingest lock | `INSERT … ON CONFLICT (event_id) DO NOTHING RETURNING id` | **Strictly better.** One round trip, no rolled-back transactions under a concurrent burst, no exception-driven control flow, no fail-open caveat. |
| Redis delivery lock | Row-level `SKIP LOCKED` claim + lease + `@Version` | The claim is now atomic in the store of record, so there is no "Redis is down" degradation path to reason about at all. |
| Redis circuit breaker | Small in-process `EndpointHealthGate` | Honest scope reduction: the brief specifies **one** endpoint. Per-instance breaker state is sufficient and its limitation is documented. |
| Kubernetes manifests | Docker Compose only | Removes ~15 files the scorecard gives no credit for and shortens reviewer setup to one command. |

Net effect: fewer components, **stronger** correctness story, and a test suite that can be made almost entirely deterministic (§12).

---

## 1. Requirement → Architecture Traceability

| # | Required behaviour (brief §"Required behavior") | Where it lives |
|---|---|---|
| 1 | Accept and retain an event before/as part of scheduling | `ingest-service`: `webhook_event` + `outbox_dispatch` in **one transaction**, before any outbound call |
| 2 | Deliver to one configurable HTTP endpoint | `delivery-service` → `WebhookHttpClient` (Spring `RestClient`), `webhook.target.url` |
| 3 | Record attempt time, number, outcome | `delivery_attempt`, one append-only row per HTTP attempt |
| 4 | Documented success policy | `ResponseClassifier` — HTTP 2xx only (§9.1) |
| 5 | Bounded retry of documented temporary failures | `RetryPolicy` + `BackoffPolicy`, `max_attempts = 5`, exponential + jitter (§9.2) |
| 6 | Expose delivery state and attempt history | `GET /api/v1/events/{eventId}`, `GET /internal/v1/deliveries/**`, JSON logs, Micrometer |
| 7 | Idempotent repeated submission | `UNIQUE(event_id)` + `ON CONFLICT DO NOTHING` at ingest **and** at delivery creation (§9.5) |

| AC | Satisfied by | Test |
|---|---|---|
| **AC1** Successful delivery | `PENDING → IN_FLIGHT → SUCCEEDED`, 1 attempt row | `SuccessfulDeliveryTest` |
| **AC2** Temporary failure then retry | `RETRYABLE_FAILURE → RETRY_SCHEDULED` with `next_attempt_at`, re-claimed, eventual `SUCCEEDED` | `RetryThenSuccessTest` |
| **AC3** Bounded failure | Attempt 5 → `FAILED_EXHAUSTED`, no 6th claim ever possible | `AttemptExhaustionTest` |
| **AC4** Idempotent ingestion | 2nd POST → `200` + same `deliveryId`, no second outbox row, no second delivery | `IdempotentIngestTest`, `ConcurrentIngestTest` |
| **AC5** Inspectable history | Composed read API, attempts ordered by `attempt_number` | `EventHistoryApiTest` |

| Reviewer focus area (brief) | Design answer |
|---|---|
| Explicit states and valid transitions | 6 states, enforced transition table on the entity, `@Version` (§9.6) |
| Persistence boundaries / behaviour after process failure | Outbox (no dual-write); `attempt_count` incremented **before** the HTTP call; lease + reaper (§10) |
| Idempotency, sequential and concurrent | Database constraints only — no application-level locking anywhere (§9.5) |
| Clear retry classification | One pure `ResponseClassifier`, published table (§9.1) |
| Useful logs / history | MDC-tagged JSON logs + full attempt table |
| Separation of ingestion, scheduling, delivery, storage | Four named collaborators, three services, two databases (§3) |
| Tests without arbitrary sleeps | Injected `Clock` + manually-ticked scheduler + synchronous executor in tests (§12.1) |

---

## 2. Design Principles

These are the rules everything else obeys. A change that breaks one of these is a design change, not an implementation detail.

1. **PostgreSQL is the single source of truth — for data *and* for scheduling.** If it is consistent, the system is consistent.
2. **Never dual-write.** One transaction commits the event and the intent to dispatch, or neither.
3. **No in-memory state is load-bearing.** Kill any process at any instant; the DB alone determines what happens next.
4. **No network I/O inside a database transaction.** Ever. Claim, commit, call, commit. (One scoped exception, §6.)
5. **Idempotency is a constraint, not a code path.** Unique indexes, `ON CONFLICT`, `SKIP LOCKED` — the database arbitrates races, not Java.
6. **Retryability is one pure function**, unit-testable in microseconds.
7. **Time is injected.** No `Instant.now()` outside the `Clock` bean; no `Thread.sleep` in tests.

---

## 3. System Architecture

```
                    ┌───────────────────────────────────────────────┐
  POST /api/v1/     │           webhook-ingest-service              │
      events   ────▶│  IngestEventUseCase                           │
                    │   └─ TX { INSERT webhook_event ON CONFLICT    │
                    │            + INSERT outbox_dispatch }         │
                    │  OutboxDispatcher  @Scheduled(500ms)          │
                    │   └─ claim SKIP LOCKED ──▶ RestClient ────────┼──┐
                    │  EventQueryService ──── RestClient ───────────┼──┤
  GET /events/{id} ◀│   (composed read)                            │  │
                    └───────────────────────────────────────────────┘  │
                              DB: webhook_ingest                       │
                                                                       │
                    ┌───────────────────────────────────────────────┐  │
                    │          webhook-delivery-service            │◀─┘
                    │  POST /internal/v1/deliveries   (idempotent) │
                    │   └─ INSERT delivery ON CONFLICT DO NOTHING  │
                    │                                              │
                    │  DeliveryScheduler  @Scheduled(200ms)        │
                    │   └─ claimDue(): SELECT … FOR UPDATE          │
                    │        SKIP LOCKED → IN_FLIGHT + lease        │
                    │              │                                │
                    │              ▼ bounded TaskExecutor           │
                    │        DeliveryExecutor                       │
                    │         ├─ EndpointHealthGate                 │
                    │         ├─ RestClient POST ──────────────────┼──▶ receiver
                    │         ├─ ResponseClassifier                 │
                    │         └─ TX { INSERT delivery_attempt       │
                    │                 + transition + schedule }     │
                    │                                              │
                    │  LeaseReaper  @Scheduled(10s)                │
                    │  GET /internal/v1/deliveries/**              │
                    └───────────────────────────────────────────────┘
                              DB: webhook_delivery
```

### 3.1 Services

| Service | Responsibility | Owns | Never does |
|---|---|---|---|
| **`webhook-ingest-service`** | HTTP ingestion, validation, idempotent acceptance, durable retention, dispatch handoff, composed read API | DB `webhook_ingest`: `webhook_event`, `outbox_dispatch` | Never calls the external webhook; never touches delivery tables |
| **`webhook-delivery-service`** | Delivery lifecycle: scheduling, claiming, the HTTP attempt, classification, retry/termination, attempt history | DB `webhook_delivery`: `delivery`, `delivery_attempt` | Never touches `webhook_event`; receives everything it needs at creation |
| **`webhook-test-receiver`** | Controllable webhook target for the demo and acceptance tests | in-memory only | Not part of the product; ~200 LOC, deliberately trivial |

**Why the boundary is real:** ingestion must stay fast and available *precisely when the receiver is down* — that is the whole point of the product. Delivery is slow, I/O-bound, restart-prone and needs its own thread budget and its own failure blast radius. Different availability profiles ⇒ different deployables. This is the brief's "separation between event ingestion, scheduling, delivery, and storage" made structural rather than just packaged.

**Honest trade-off for `SUBMISSION.md`:** a single Spring Boot service would be ~15% less code. The split is justified by the availability argument above and by the brief explicitly rewarding that separation — not by microservices being intrinsically better at this size.

### 3.2 Inter-service communication — all Spring `RestClient`

| Path | Call | Semantics |
|---|---|---|
| ingest → delivery | `POST /internal/v1/deliveries` (from the outbox dispatcher) | **Idempotent**, retried with backoff until acknowledged |
| ingest → delivery | `GET /internal/v1/deliveries?eventId=` (composed read) | Read-only, **degrades gracefully** |
| delivery → receiver | `POST {webhook.target.url}` | The product's core outbound call |

Composed-read degradation: if delivery-service is unreachable, `GET /api/v1/events/{id}` returns `200` with the event plus `"delivery": null, "deliveryLookupError": "…"` — never a `500`. A deliberate, documented partial-failure behaviour.

---

## 4. Data Model

### 4.1 `webhook_ingest` database

**`webhook_event`** — the durable record of acceptance

| Column | Type | Notes |
|---|---|---|
| `id` | `uuid` PK | surrogate |
| `event_id` | `varchar(128)` | **`UNIQUE` — the idempotency invariant** |
| `type` | `varchar(128)` | |
| `occurred_at` | `timestamptz` | caller-supplied |
| `payload` | `jsonb` | forwarded verbatim |
| `payload_hash` | `char(64)` | SHA-256, detects *same id / different body* |
| `received_at` | `timestamptz` | |
| `duplicate_submission_count` | `int` default 0 | observability for AC4 |
| `last_duplicate_at` | `timestamptz` null | |

Indexes: `UNIQUE(event_id)`, `INDEX(received_at DESC)`.

**`outbox_dispatch`** — the durable intent to schedule a delivery

| Column | Type | Notes |
|---|---|---|
| `id` | `bigserial` PK | monotonic → natural FIFO |
| `event_id` | `varchar(128)` | **`UNIQUE`** — one dispatch intent per event, structurally |
| `payload` | `jsonb` | the serialized `CreateDeliveryRequest` |
| `status` | `varchar(16)` | `PENDING｜DISPATCHED｜FAILED` |
| `attempts` | `int` default 0 | dispatcher's own bounded retry |
| `next_dispatch_at` | `timestamptz` | dispatcher backoff |
| `delivery_id` | `uuid` null | returned by delivery-service; closes the loop |
| `last_error` | `text` null | |
| `created_at`, `dispatched_at` | `timestamptz` | |

Index: partial `INDEX(next_dispatch_at) WHERE status = 'PENDING'`.

`UNIQUE(event_id)` on the outbox is the structural expression of *"does not schedule a second independent delivery job."*

### 4.2 `webhook_delivery` database

**`delivery`** — one logical delivery job per event; also the work queue

| Column | Type | Notes |
|---|---|---|
| `id` | `uuid` PK | also the receiver-facing `X-Idempotency-Key` |
| `event_id` | `varchar(128)` | **`UNIQUE`** — consumer-side idempotency |
| `event_type`, `occurred_at`, `payload` | | copied at creation; delivery-service is self-sufficient |
| `target_url` | `text` | resolved at creation → history stays auditable if config changes |
| `status` | `varchar(24)` | `PENDING｜IN_FLIGHT｜RETRY_SCHEDULED｜SUCCEEDED｜FAILED_PERMANENT｜FAILED_EXHAUSTED` |
| `attempt_count` | `int` default 0 | incremented **at claim time**, before the HTTP call |
| `max_attempts` | `int` | snapshotted from config at creation |
| `next_attempt_at` | `timestamptz` | **the scheduling column**; set to `now()` at creation |
| `lease_owner` | `varchar(64)` null | `hostname:pid:uuid` |
| `lease_expires_at` | `timestamptz` null | drives crash recovery |
| `terminal_reason` | `varchar(32)` null | `NON_RETRYABLE_RESPONSE｜ATTEMPTS_EXHAUSTED` |
| `last_outcome` | `varchar(24)` null | denormalised for cheap listing |
| `created_at`, `first_attempt_at`, `completed_at` | `timestamptz` | |
| `version` | `bigint` | `@Version` |

Indexes:
- `UNIQUE(event_id)`
- **`INDEX(next_attempt_at) WHERE status IN ('PENDING','RETRY_SCHEDULED')`** — the hot claim index; partial, so it stays small as completed rows accumulate
- `INDEX(lease_expires_at) WHERE status = 'IN_FLIGHT'` — the reaper index
- `INDEX(status, created_at DESC)` — listing / dead-letter view

**`delivery_attempt`** — append-only, never updated after insert

| Column | Type | Notes |
|---|---|---|
| `id` | `uuid` PK | |
| `delivery_id` | `uuid` FK → `delivery(id)` ON DELETE CASCADE | |
| `attempt_number` | `int` | **`UNIQUE(delivery_id, attempt_number)`** — a double attempt is impossible by construction |
| `started_at`, `completed_at` | `timestamptz` | |
| `duration_ms` | `int` | |
| `outcome` | `varchar(24)` | `SUCCESS｜RETRYABLE_FAILURE｜PERMANENT_FAILURE` |
| `http_status` | `int` null | null for transport errors |
| `error_class` | `varchar(32)` null | `CONNECT_TIMEOUT｜READ_TIMEOUT｜CONNECTION_REFUSED｜DNS_FAILURE｜TLS_ERROR｜MALFORMED_URL｜CIRCUIT_OPEN｜UNKNOWN` (see §21 D1) |
| `error_message` | `text` null | truncated 512 |
| `response_body_snippet` | `text` null | **truncated to 1 KB** — bounded storage |
| `retry_after_seconds` | `int` null | parsed from the header when present |
| `next_attempt_at` | `timestamptz` null | what *this* attempt scheduled |
| `worker_id` | `varchar(64)` | which instance ran it |

Mapping: `@OneToMany(fetch = LAZY)`; history is loaded through an explicit `findByDeliveryIdOrderByAttemptNumberAsc` — no `EAGER`, no N+1.

---

## 5. Scheduling & Concurrency — the Core Mechanism

This replaces Kafka entirely. It is the standard "PostgreSQL as a job queue" pattern.

### 5.1 The claim

`DeliveryScheduler` runs `@Scheduled(fixedDelay = 200ms)` on **every** instance. Each tick:

```
capacity = executor.maxPoolSize - executor.activeCount     // claim only what you can run
if capacity <= 0 → return                                  // natural backpressure

TX (short, no I/O):
  ids = SELECT id FROM delivery
        WHERE status IN ('PENDING','RETRY_SCHEDULED')
          AND next_attempt_at <= :now
        ORDER BY next_attempt_at
        LIMIT :capacity
        FOR UPDATE SKIP LOCKED                              -- native query
  entities = repository.findAllById(ids)                    -- rows already locked
  for each: transitionTo(IN_FLIGHT)                         -- guarded by the state machine
            attempt_count += 1
            lease_owner = workerId
            lease_expires_at = now + httpTimeout + slack
            first_attempt_at ??= now
COMMIT                                                      -- locks released, claim is durable

for each claimed → executor.submit(() -> deliveryExecutor.execute(delivery))
```

Why this shape:
- **`FOR UPDATE SKIP LOCKED`** means N instances can poll the same table concurrently and provably never hand the same row to two workers. This is the same primitive every serious DB-backed queue uses.
- **Two-step (select ids → load entities)** keeps the state transition inside the JPA entity where the transition table is enforced, instead of scattering it into a bulk `UPDATE`.
- **`attempt_count` is incremented in the claim transaction**, before any HTTP call. This is what makes the attempt bound survive a crash: a process that dies mid-flight has already "spent" the attempt.
- **Claim ≤ free capacity** turns the executor into backpressure. The queue never grows unboundedly in memory; unclaimed work simply stays in Postgres.

### 5.2 The execution (no transaction held across I/O)

```
DeliveryExecutor.execute(delivery):
  if !endpointHealthGate.allow(host):
      record attempt(outcome=RETRYABLE_FAILURE, error_class=CIRCUIT_OPEN, no HTTP call)
      → reschedule
  started = clock.instant()
  result  = webhookHttpClient.post(delivery)        ← outside any TX
  outcome = responseClassifier.classify(result)
  endpointHealthGate.record(host, outcome)

  TX:
    INSERT delivery_attempt(...)                     ← unique (delivery_id, attempt_number)
    switch outcome:
      SUCCESS            → SUCCEEDED,        completed_at = now, clear lease
      PERMANENT_FAILURE  → FAILED_PERMANENT, terminal_reason = NON_RETRYABLE_RESPONSE
      RETRYABLE, n < max → RETRY_SCHEDULED,  next_attempt_at = backoff.next(n), clear lease
      RETRYABLE, n = max → FAILED_EXHAUSTED, terminal_reason = ATTEMPTS_EXHAUSTED
  COMMIT
```

A crash anywhere before that commit leaves the row `IN_FLIGHT` with an expiring lease — recoverable and visible.

### 5.3 The reaper

`LeaseReaper` `@Scheduled(fixedDelay = 10s)`, plus **one immediate run at application start** (`ApplicationReadyEvent`):

```sql
UPDATE delivery
   SET status = 'RETRY_SCHEDULED',
       next_attempt_at = now(),
       lease_owner = NULL, lease_expires_at = NULL, version = version + 1
 WHERE status = 'IN_FLIGHT' AND lease_expires_at < now();
```

Deliveries whose `attempt_count` already equals `max_attempts` are reaped straight to `FAILED_EXHAUSTED` instead — the bound is never exceeded by a crash.

### 5.4 Thread & connection budget

| Setting | Value | Reason |
|---|---|---|
| `delivery.executor.core/max` | 8 | bounded concurrent HTTP attempts per instance |
| `delivery.executor.queueCapacity` | 0 (`SynchronousQueue`) | the queue lives in Postgres, not in RAM |
| rejection policy | release the lease (`next_attempt_at = now`) | never silently drop |
| Hikari `maximumPoolSize` | 12 (= 8 workers + poller + reaper + API + slack) | deadlock-free by construction |
| `RestClient` pool `maxTotal` / `maxPerRoute` | 20 / 10 | a slow receiver cannot exhaust connections |

### 5.5 Concurrency safety summary

| Race | Prevented by |
|---|---|
| Two instances claim the same delivery | `FOR UPDATE SKIP LOCKED` |
| Reaper and a live worker touch the same row | Row lock + `@Version` optimistic locking |
| Two attempts write the same attempt number | `UNIQUE(delivery_id, attempt_number)` |
| Two concurrent ingests of the same `eventId` | `INSERT … ON CONFLICT (event_id) DO NOTHING` |
| Two dispatcher instances dispatch the same outbox row | `FOR UPDATE SKIP LOCKED` on `outbox_dispatch` |
| Two dispatches create two deliveries | `UNIQUE(delivery.event_id)` + `ON CONFLICT DO NOTHING` |
| An illegal state change from anywhere | `Delivery.transitionTo()` transition table; no public setters |

---

## 6. Persistence Flow, End to End

**Ingest (one transaction, one round trip):**

```sql
INSERT INTO webhook_event (id, event_id, type, occurred_at, payload, payload_hash, received_at)
VALUES (…) ON CONFLICT (event_id) DO NOTHING RETURNING id;
```
- **Row returned** → genuinely new → `INSERT outbox_dispatch (…) ON CONFLICT (event_id) DO NOTHING` → commit → `201 Created`.
- **No row returned** → duplicate → `SELECT` the existing event, compare `payload_hash`:
  - match → `UPDATE … SET duplicate_submission_count = duplicate_submission_count + 1, last_duplicate_at = now()` → `200 OK` with the existing `deliveryId`.
  - mismatch → `409 Conflict`.

No exception-driven flow, no rolled-back transactions under a 100-way concurrent burst, and no application lock. The database is the arbiter.

**Dispatch (`OutboxDispatcher`, `@Scheduled(fixedDelay = 500ms)`):**

```
TX: claim up to N rows  WHERE status='PENDING' AND next_dispatch_at <= now
                        ORDER BY id  FOR UPDATE SKIP LOCKED
    (rows stay locked for the duration of the tick)
for each: RestClient POST /internal/v1/deliveries  with header Idempotency-Key: {eventId}
    2xx → status=DISPATCHED, delivery_id = response.deliveryId, dispatched_at = now
    5xx / transport → attempts++, next_dispatch_at = now + backoff(attempts), last_error
    attempts > 10 → status=FAILED, last_error kept, surfaced via GET /api/v1/events/{id}
COMMIT
```

Because the receiving endpoint is idempotent on `event_id`, a dispatcher crash between the POST and the status update causes exactly one harmless repeat — it returns `200` with the existing `deliveryId`. **No duplicate delivery job, no duplicate webhook call.**

> **Scoped exception to principle 4.** This tick holds a DB transaction across an HTTP call. It is deliberate and tightly scoped: batch size 20, 2 s connect / 5 s read timeout, and it is an internal service-to-service call, not the untrusted external receiver. The alternative (claim-commit-call-commit) is documented in `SUBMISSION.md` as the change to make if the dispatcher ever became hot.

**Creation (delivery-service):**

```sql
INSERT INTO delivery (…, status='PENDING', next_attempt_at = now(), max_attempts = :cfg)
VALUES (…) ON CONFLICT (event_id) DO NOTHING RETURNING id;
```
Returned → `201 {deliveryId}`. Not returned → `SELECT` existing → `200 {deliveryId}`.

**Attempt:** claim TX → HTTP (no TX) → outcome TX. Exactly as §5.2.

---

## 7. API Design

### 7.1 `POST /api/v1/events` — ingest

```json
{ "eventId": "evt_123", "type": "incident.created",
  "occurredAt": "2026-09-15T10:00:00Z",
  "payload": { "incidentId": "inc_456", "severity": "high" } }
```

| Case | Status | Body |
|---|---|---|
| New | `201` + `Location: /api/v1/events/evt_123` | `{eventId, status:"ACCEPTED", duplicate:false}` |
| Same id, same payload | `200` | `{eventId, deliveryId, status:<current>, duplicate:true, duplicateSubmissionCount:2}` |
| Same id, **different** payload | `409` problem+json | `payloadMismatch` — a stable identifier must denote a stable event; silently ignoring a changed body would hide a caller bug |
| Invalid | `400` problem+json | field errors |
| DB unavailable | `503` problem+json | nothing accepted, nothing lost, caller retries |

Validation: `eventId` non-blank ≤128, `type` non-blank ≤128, `occurredAt` ISO-8601 and not more than 24 h in the future, `payload` present and ≤256 KB.

### 7.2 `GET /api/v1/events/{eventId}` — the AC5 view

```json
{
  "eventId": "evt_123",
  "type": "incident.created",
  "occurredAt": "2026-09-15T10:00:00Z",
  "receivedAt": "2026-09-15T10:00:01.220Z",
  "duplicateSubmissionCount": 1,
  "dispatch": { "status": "DISPATCHED", "attempts": 1 },
  "delivery": {
    "deliveryId": "8f2c…", "status": "SUCCEEDED",
    "targetUrl": "http://receiver:8082/receive",
    "attemptCount": 3, "maxAttempts": 5,
    "nextAttemptAt": null, "terminalReason": null,
    "firstAttemptAt": "…", "completedAt": "…",
    "attempts": [
      { "attemptNumber": 1, "startedAt": "…", "durationMs": 1034,
        "outcome": "RETRYABLE_FAILURE", "httpStatus": 503,
        "responseBodySnippet": "service unavailable",
        "nextAttemptAt": "…", "workerId": "delivery-1:7:a3f…" },
      { "attemptNumber": 2, "outcome": "RETRYABLE_FAILURE",
        "httpStatus": null, "errorClass": "READ_TIMEOUT", "…": "…" },
      { "attemptNumber": 3, "outcome": "SUCCESS", "httpStatus": 200, "…": "…" }
    ]
  }
}
```

### 7.3 Full surface

| Service | Endpoint | Purpose |
|---|---|---|
| ingest | `POST /api/v1/events` | Idempotent ingestion |
| ingest | `GET /api/v1/events/{eventId}` | Composed state + ordered history (AC5) |
| ingest | `GET /api/v1/events?page=&size=` | Paged list for the demo |
| delivery | `POST /internal/v1/deliveries` | Idempotent delivery creation (called by the dispatcher) |
| delivery | `GET /internal/v1/deliveries?eventId=` | Lookup by event id |
| delivery | `GET /internal/v1/deliveries/{id}` | Direct inspection |
| delivery | `GET /internal/v1/deliveries/{id}/attempts` | Ordered attempt history |
| delivery | `GET /internal/v1/deliveries?status=FAILED_EXHAUSTED` | **The dead-letter view** |
| delivery | `POST /internal/v1/deliveries/{id}/replay` | *Optional*: terminal → `RETRY_SCHEDULED`, `max_attempts += N`; attempt numbering **continues**, history is never rewritten |
| both | `/actuator/health{,/liveness,/readiness}`, `/actuator/prometheus` | Ops |
| receiver | `POST /receive` | The webhook target |
| receiver | `POST /control/mode` | `{mode: ALWAYS_OK｜ALWAYS_FAIL｜FAIL_N_THEN_OK｜TIMEOUT, failCount, status, delayMs}` |
| receiver | `GET /control/received` | Bodies + headers received — proves duplicate detection on camera |
| receiver | `POST /control/reset` | Test isolation |

All errors are RFC 7807 `application/problem+json` via one `@RestControllerAdvice` per service.

---

## 8. Webhook Delivery Mechanics

`RestClient` over Apache HttpClient 5:

- `connectTimeout = 2s`, `responseTimeout = 5s` (config-driven; the lease is `responseTimeout + 10s`)
- `redirectsEnabled = false` — a `3xx` is a misconfiguration, not a delivery path
- pool `maxTotal = 20`, `maxPerRoute = 10`
- response body read with a **1 KB cap**; remainder drained and discarded so the connection returns to the pool

Outbound headers:

| Header | Value |
|---|---|
| `Content-Type` | `application/json` |
| `X-Webhook-Event-Id` | `evt_123` |
| `X-Webhook-Delivery-Id` | delivery UUID |
| `X-Idempotency-Key` | **delivery UUID — identical across all retries**; how the receiver dedupes |
| `X-Webhook-Attempt` | `1..5` |
| `X-Webhook-Timestamp` | RFC 3339 |
| `X-Webhook-Signature` | *optional*, `sha256=HMAC(secret, timestamp + "." + body)`, **disabled by default** (brief marks signing secondary) |

---

## 9. Delivery Semantics

### 9.1 Response classification — the documented policy

| Class | Condition | Outcome |
|---|---|---|
| **Success** | HTTP `200–299` | `SUCCESS` → terminal |
| **Retryable** | `408`, `425`, `429`, `500`, `502`, `503`, `504` | `RETRYABLE_FAILURE` |
| **Retryable (transport)** | connect timeout, read timeout, connection refused, DNS failure, connection reset | `RETRYABLE_FAILURE` |
| **Permanent** | every other `4xx` (`400/401/403/404/405/409/410/413/422/…`) | `PERMANENT_FAILURE` → terminal, **no retry** |
| **Permanent** | `501`, `505`, `1xx`, `3xx` (not followed), TLS handshake failure, malformed URL | `PERMANENT_FAILURE` |

**Rationale (the "clear retry classification" signal):** retry only what a later attempt could plausibly succeed at. A `422` means the body is wrong; it will be equally wrong in 30 seconds. Retrying it burns capacity and delays the deliveries that could succeed.

### 9.2 Retry limit and exponential backoff

```
delay(n) = min(baseDelay × multiplier^(n-1), maxDelay) × jitter(0.8 … 1.2)
next_attempt_at = clock.now() + delay(attemptCount)
```

| Profile | base | multiplier | max | attempts | Resulting schedule |
|---|---|---|---|---|---|
| `default` | 5 s | 5 | 10 min | 5 | 5 s → 25 s → 125 s → 600 s |
| `demo` | 1 s | 2 | 30 s | 5 | 1 s → 2 s → 4 s → 8 s (~15 s end-to-end, fits the video) |
| `test` | 0 | — | 0 | 5 | immediate; time is driven by the test's `Clock` |

`Retry-After` on `429`/`503` overrides the computed delay, clamped to `[1s, maxDelay]`, and is recorded on the attempt row. Jitter prevents synchronised retry storms when a receiver recovers — mattering more here than under Kafka, because every instance polls the same index.

### 9.3 Delivery guarantee

**At-least-once, per-delivery ordered, bounded at 5 attempts.** Exactly-once across an external HTTP boundary is impossible: the receiver can commit and the response can still be lost. Receivers must dedupe on `X-Idempotency-Key`. Stated prominently in `README.md` and `SUBMISSION.md`.

Ordering is free here: a delivery is `IN_FLIGHT` in exactly one place at a time, so attempt *N+1* cannot start before attempt *N* has been recorded.

### 9.4 What can still cause the receiver to observe a duplicate

1. Receiver commits, the response is lost / times out → classified `READ_TIMEOUT` → retryable → re-sent. **Inherent.**
2. Process dies after the HTTP call returns but before the outcome transaction commits → lease expires → reaper re-queues → re-sent. **Inherent.**
3. A network partition makes the lease expire while the original worker is still blocked in `read()` → the reaper re-queues and a second worker calls. Mitigated by `lease = responseTimeout + 10 s` (the original call *must* have timed out first) but not eliminated under severe clock skew or GC pause.

Not causes: duplicate ingestion (`UNIQUE(event_id)`), duplicate dispatch (`ON CONFLICT`), double claim (`SKIP LOCKED`), double attempt row (`UNIQUE(delivery_id, attempt_number)`).

### 9.5 Idempotent ingestion (AC4)

Three independent layers, each a database constraint:

| Layer | Constraint | Guarantees |
|---|---|---|
| Ingest | `UNIQUE(webhook_event.event_id)` + `ON CONFLICT DO NOTHING` | Exactly one logical event, under any concurrency, across any number of ingest instances |
| Handoff | `UNIQUE(outbox_dispatch.event_id)` | Exactly one dispatch intent |
| Delivery | `UNIQUE(delivery.event_id)` + `ON CONFLICT DO NOTHING` | Exactly one delivery job, even if the dispatcher POSTs twice |

No application-level lock exists anywhere in this path. That is the point: it removes the entire class of "what if the lock service is down" reasoning.

### 9.6 State machine

```
            ┌──────────┐
            │ PENDING  │  next_attempt_at = created_at
            └────┬─────┘
                 │ claim (SKIP LOCKED, attempt_count++)
                 ▼
          ┌─────────────┐   SUCCESS    ┌────────────┐
          │  IN_FLIGHT  │─────────────▶│ SUCCEEDED  │ ■
          └──┬───┬───┬──┘              └────────────┘
  retryable  │   │   │ permanent       ┌──────────────────┐
  n < max    │   │   └────────────────▶│ FAILED_PERMANENT │ ■
             │   │ retryable, n = max  └──────────────────┘
             │   └────────────────────▶┌───────────────────┐
             ▼                         │ FAILED_EXHAUSTED  │ ■
      ┌──────────────────┐             └───────────────────┘
      │ RETRY_SCHEDULED  │                    │
      └────────┬─────────┘                    │ replay (optional)
               │ next_attempt_at <= now       │
               └──────── claim ───────────────┘
```

| From | To | Guard |
|---|---|---|
| `PENDING` | `IN_FLIGHT` | claimed ∧ `attempt_count < max_attempts` |
| `RETRY_SCHEDULED` | `IN_FLIGHT` | claimed ∧ `next_attempt_at <= now` ∧ `attempt_count < max_attempts` |
| `IN_FLIGHT` | `SUCCEEDED` | `outcome = SUCCESS` |
| `IN_FLIGHT` | `FAILED_PERMANENT` | `outcome = PERMANENT_FAILURE` |
| `IN_FLIGHT` | `RETRY_SCHEDULED` | `outcome = RETRYABLE_FAILURE ∧ attempt_count < max_attempts` |
| `IN_FLIGHT` | `FAILED_EXHAUSTED` | `outcome = RETRYABLE_FAILURE ∧ attempt_count = max_attempts` |
| `IN_FLIGHT` | `RETRY_SCHEDULED` | **reaper only**: `lease_expires_at < now ∧ attempt_count < max_attempts` |
| `IN_FLIGHT` | `FAILED_EXHAUSTED` | **reaper only**: `lease_expires_at < now ∧ attempt_count = max_attempts` |
| any terminal | anything | **rejected** → `IllegalStateTransitionException` (except explicit replay) |

Enforced in exactly one place — `Delivery.transitionTo(DeliveryStatus)` — against a static `EnumMap<DeliveryStatus, EnumSet<DeliveryStatus>>`, with `@Version` guarding the write. The entity exposes intent methods (`claim()`, `recordSuccess()`, `recordRetryableFailure()`, …), never `setStatus()`.

---

## 10. Failure Handling & Restart Recovery

| Failure | Behaviour | Recovery mechanism |
|---|---|---|
| Delivery instance killed **mid-HTTP** | Row stays `IN_FLIGHT`; `attempt_count` already spent | `LeaseReaper` at lease expiry, **and immediately on next startup** |
| Delivery instance killed **after HTTP, before commit** | Same as above → one duplicate delivery (documented) | Reaper |
| Ingest instance killed **after commit, before dispatch** | `outbox_dispatch` row is `PENDING` | `OutboxDispatcher` on next tick / next startup |
| Ingest instance killed **mid-transaction** | Transaction rolls back; caller gets no `201`, so it retries; `ON CONFLICT` makes that safe | Caller retry |
| Delivery-service down | Ingest keeps returning `201`; outbox backs off exponentially; no data loss | Dispatcher |
| Postgres down (ingest) | `POST` → `503`; nothing accepted, nothing lost | Caller retry |
| Postgres down (delivery) | Claim transaction fails; no rows move; scheduler logs and retries next tick | Next tick |
| Receiver down / refusing | Retryable transport error → exponential backoff → `FAILED_EXHAUSTED` at attempt 5 → dead-letter view | Bounded by design |
| Receiver slow (> 5 s) | Read timeout → retryable; `maxPerRoute = 10` caps thread/connection consumption | Timeout + pool |
| Receiver flapping | `EndpointHealthGate` opens after 10 consecutive retryable failures, half-opens after 30 s; gated attempts are still **recorded** as attempts with `error_class = CIRCUIT_OPEN` | In-process gate |
| Clock skew between instances | All comparisons use `now()` evaluated by **PostgreSQL** in claim/reaper queries; the Java `Clock` is used only for `next_attempt_at` arithmetic and record-keeping | Single clock authority |
| Slow-consumer starvation | Claim batch ≤ free executor capacity; unclaimed work waits in Postgres, never in RAM | Backpressure |

**Startup recovery sequence** (both services, on `ApplicationReadyEvent`): Flyway migrate → reap expired leases once → log a recovery summary (`reclaimed=N, pendingOutbox=M, dueDeliveries=K`) → enable scheduling. **Nothing is rebuilt from memory, because nothing lives in memory.**

`EndpointHealthGate` is the one exception: it is per-instance and resets on restart. Documented as a deliberate limitation — with one configured endpoint and a hard attempt bound, the cost of a cold gate is at most a few extra attempts.

---

## 11. Observability

- **Structured JSON logs** (`logstash-logback-encoder`). MDC — `eventId`, `deliveryId`, `attemptNumber`, `workerId` — populated by one servlet filter and one executor task decorator, not scattered through business code.
- One INFO line per attempt (outcome, status, duration, next attempt), WARN on terminal failure, WARN on lease reclaim, ERROR on outbox dispatch exhaustion.
- **Micrometer → `/actuator/prometheus`**: `webhook_delivery_attempts_total{outcome}`, `webhook_delivery_duration_seconds`, `webhook_delivery_terminal_total{reason}`, `webhook_ingest_duplicates_total`, `webhook_outbox_pending`, `webhook_delivery_due_backlog`, `webhook_lease_reclaimed_total`, `webhook_endpoint_gate_state`.
- Two gauges worth calling out in the demo: **`webhook_delivery_due_backlog`** (rows past `next_attempt_at` — the real queue depth) and **`webhook_outbox_pending`**.
- Production alerts to propose in `SUBMISSION.md`: terminal-failure rate, oldest pending outbox age, due-backlog growth, p99 attempt duration, lease-reclaim rate (a proxy for instance instability).

---

## 12. Testing Architecture

### 12.1 Determinism strategy — the headline

The brief penalises "tests that depend on arbitrary sleep timing". Removing Kafka lets the acceptance tests become **fully synchronous and time-controlled**:

1. **`Clock` is a bean.** Tests bind a `MutableTestClock`; production binds `Clock.systemUTC()`. Advancing time is `clock.advance(Duration.ofSeconds(30))`.
2. **Schedulers are manually ticked.** `@Scheduled` is disabled under the `test` profile; `DeliveryScheduler.runOnce()` and `OutboxDispatcher.runOnce()` are ordinary public methods the test calls.
3. **The executor is synchronous in tests.** The `TaskExecutor` bean is `ThreadPoolTaskExecutor` in production and `SyncTaskExecutor` under the `test` profile — so `runOnce()` **returns only after every claimed delivery has fully completed**.

Consequence: the AC3 exhaustion test is

```
receiver.mode(ALWAYS_FAIL, 500)
ingest(event) ; dispatcher.runOnce()
repeat 5 times { scheduler.runOnce() ; clock.advance(10.minutes) }
assert status == FAILED_EXHAUSTED && attempts.size() == 5
scheduler.runOnce()                 // a 6th tick
assert attempts.size() == 5         // still 5 — the bound holds
```

Zero sleeps, zero Awaitility, zero flakiness, milliseconds of wall clock. A small number of tests (§12.4) deliberately run with real scheduling to prove the wiring itself.

### 12.2 Unit — JUnit 5 + Mockito, no containers

| Test | Proves |
|---|---|
| `ResponseClassifierTest` | The entire §9.1 table, `@ParameterizedTest` across statuses 100–599 and every transport exception |
| `BackoffPolicyTest` | Exponential growth, `maxDelay` cap, jitter bounds, `Retry-After` override + clamping — fixed `Clock` |
| `DeliveryStateMachineTest` | Every legal transition allowed, **every illegal one rejected** (full 6×6 matrix) |
| `DeliveryTest` | `claim()` increments and leases; `recordRetryableFailure()` at `n = max` yields `FAILED_EXHAUSTED`, not `RETRY_SCHEDULED` |
| `IngestEventUseCaseTest` | Mockito: `ON CONFLICT` returns empty → no outbox row written, duplicate counter incremented |
| `PayloadHashTest` | Same-id/different-body detection; hash stability across key ordering |
| `OutboxDispatcherTest` | Mockito: `5xx` → backoff and `attempts++`; `2xx` → `DISPATCHED`; exhaustion → `FAILED` |
| `WebhookHttpClientTest` | `MockRestServiceServer`: headers present, idempotency key stable across attempts, body cap, redirects not followed |
| `EndpointHealthGateTest` | Open/half-open/close thresholds against a fixed `Clock` |

### 12.3 Persistence — `@DataJpaTest` + Testcontainers `PostgreSQLContainer`

**Never H2** — the behaviours under test *are* PostgreSQL behaviours.

| Test | Proves |
|---|---|
| `EventUniquenessTest` | `ON CONFLICT DO NOTHING RETURNING` semantics; second insert returns empty |
| `SkipLockedClaimTest` | Two concurrent transactions claiming the same due row → **disjoint** result sets, neither blocks |
| `OptimisticLockingTest` | Concurrent reaper + worker write → one `OptimisticLockingFailureException`, state stays valid |
| `AttemptUniquenessTest` | Duplicate `(delivery_id, attempt_number)` insert is rejected |
| `ClaimIndexTest` | `EXPLAIN` on the claim query uses the partial index (guards against a silent seq-scan regression) |

### 12.4 Acceptance — `@SpringBootTest` + Testcontainers

One shared singleton `PostgreSQLContainer` for the whole suite (`@DynamicPropertySource`). The webhook target is the real **test-receiver** where behaviour matters, and **WireMock** where a raw transport failure must be simulated. Ingest and delivery run in one test context with the inter-service `RestClient` pointed at local ports, so the boundary is exercised for real.

| Test | AC | Asserts |
|---|---|---|
| `SuccessfulDeliveryTest` | AC1 | `SUCCEEDED`, exactly 1 attempt, `outcome=SUCCESS`, receiver got the body **once** with the expected headers |
| `RetryThenSuccessTest` | AC2 | `FAIL_N_THEN_OK(2, 503)` → 3 attempts, outcomes `[RETRYABLE, RETRYABLE, SUCCESS]`, `next_attempt_at` set on 1 and 2, growing backoff, final `SUCCEEDED` |
| `AttemptExhaustionTest` | AC3 | `ALWAYS_FAIL(500)` → exactly 5 attempts, `FAILED_EXHAUSTED`, `terminal_reason=ATTEMPTS_EXHAUSTED`, appears in the dead-letter view, **no 6th attempt after further ticks** |
| `PermanentFailureTest` | — | `422` → exactly 1 attempt, `FAILED_PERMANENT`, `next_attempt_at` null |
| `TransportFailureRetryTest` | — | connection refused → `error_class = CONNECTION_REFUSED`, retried |
| `IdempotentIngestTest` | AC4 | Same `eventId` twice → `201` then `200`, same `deliveryId`, 1 event / 1 outbox / 1 delivery row, receiver called once |
| `ConcurrentIngestTest` | AC4 | 50 threads × same `eventId` → exactly one `201`, forty-nine `200`, exactly 1 of each row, `duplicate_submission_count = 49` |
| `PayloadMismatchTest` | — | Same id, different payload → `409`, no second delivery |
| `EventHistoryApiTest` | AC5 | `GET /events/{id}` returns state + attempts strictly ordered by `attempt_number` with every recorded field |
| `CrashRecoveryTest` | — | Force `IN_FLIGHT` with an expired lease → reaper → re-claimed → `SUCCEEDED`; and at `attempt_count = max` → reaped directly to `FAILED_EXHAUSTED` |
| `OutboxRecoveryTest` | — | Delivery-service returns `503` → outbox stays `PENDING` with backoff → restored → next tick dispatches → delivery completes. Proves zero loss. |
| `DeliveryServiceDownReadTest` | — | Composed read returns `200` with `delivery: null` and an error note, not a `500` |
| `RealSchedulingSmokeTest` | — | The **only** test with `@Scheduled` enabled and the real executor: submit → Awaitility until `SUCCEEDED` (5 s ceiling) under the `demo` profile. Proves the wiring, not the timing. |

`mvn verify` → Surefire (unit + `@DataJpaTest`) then Failsafe (`*IT`). Only Docker is required. No paid service anywhere. Target: full suite under ~90 s.

---

## 13. Project & Package Structure

Maven multi-module; the parent POM pins the Spring Boot BOM, Java 21, and the Testcontainers BOM.

```
webhook-retry-engine/
├── pom.xml
├── README.md                        # 10-minute reviewer path
├── SUBMISSION.md
├── Makefile                         # up / down / test / demo / logs
│
├── webhook-common/                  # wire contracts + shared enums ONLY
│   └── .../common/
│       ├── api/      CreateDeliveryRequest, DeliveryView, AttemptView, ProblemCodes
│       └── model/    DeliveryStatus, AttemptOutcome, TerminalReason, ErrorClass
│
├── webhook-ingest-service/
│   └── src/main/java/.../ingest/
│       ├── IngestApplication.java
│       ├── api/            EventController, dto/, GlobalExceptionHandler
│       ├── application/    IngestEventUseCase, EventQueryService, OutboxDispatcher
│       ├── domain/         WebhookEvent, OutboxDispatch, PayloadHasher,
│       │                  PayloadMismatchException
│       ├── infrastructure/
│       │   ├── persistence/ WebhookEventRepository (+ native ON CONFLICT insert),
│       │   │                OutboxDispatchRepository (+ SKIP LOCKED claim)
│       │   └── client/      DeliveryServiceClient          (Spring RestClient)
│       └── config/         RestClientConfig, ClockConfig, SchedulingConfig,
│                           IngestProperties
│       └── resources/db/migration/V1__ingest_schema.sql
│
├── webhook-delivery-service/
│   └── src/main/java/.../delivery/
│       ├── DeliveryApplication.java
│       ├── api/            DeliveryController, dto/
│       ├── application/    CreateDeliveryUseCase,      ← idempotent creation
│       │                  DeliveryScheduler,          ← claim (the queue)
│       │                  DeliveryExecutor,           ← attempt + outcome
│       │                  LeaseReaper,                ← crash recovery
│       │                  DeliveryQueryService, ReplayDeliveryUseCase
│       ├── domain/         Delivery, DeliveryAttempt,
│       │                  DeliveryStateMachine,       ← pure, no Spring
│       │                  ResponseClassifier,         ← pure
│       │                  BackoffPolicy,              ← pure
│       │                  DeliveryOutcome, IllegalStateTransitionException
│       ├── infrastructure/
│       │   ├── persistence/ DeliveryRepository (+ SKIP LOCKED claim,
│       │   │                native ON CONFLICT insert, reaper update),
│       │   │                DeliveryAttemptRepository
│       │   ├── http/        WebhookHttpClient          (Spring RestClient)
│       │   └── health/      EndpointHealthGate
│       └── config/         WebhookClientConfig, ExecutorConfig, ClockConfig,
│                           SchedulingConfig, RetryProperties
│       └── resources/db/migration/V1__delivery_schema.sql
│
├── webhook-test-receiver/           # ~200 LOC, in-memory, not product code
│   └── .../receiver/  ReceiverController, ControlController, ReceiverState
│
└── deploy/docker/
    ├── docker-compose.yml
    ├── Dockerfile.service           # one parameterised multi-stage build
    ├── initdb.sql                   # CREATE DATABASE webhook_ingest / webhook_delivery
    └── .env.example
```

**The convention, stated once:** `api` (transport) → `application` (orchestration + transaction boundaries) → `domain` (entities and pure policy) ← `infrastructure` (adapters). Dependencies point inward. `ResponseClassifier`, `BackoffPolicy` and `DeliveryStateMachine` carry **no Spring or JPA annotations at all** — which is exactly why they unit-test in microseconds and why the whole retry policy can be reviewed in one sitting.

`webhook-common` holds wire contracts and enums only. No shared entities, no shared services — a shared domain module would quietly dissolve the service boundary.

---

## 14. Docker (the only deployment; the reviewer's path)

`deploy/docker/docker-compose.yml` — `docker compose up --build` gives a working system in one command.

| Service | Image | Port | Health check |
|---|---|---|---|
| `postgres` | `postgres:16-alpine` | 5432 | `pg_isready` |
| `ingest-service` | local multi-stage build | 8080 | `/actuator/health/readiness` |
| `delivery-service` | local multi-stage build | 8081 | `/actuator/health/readiness` |
| `test-receiver` | local multi-stage build | 8082 | `/actuator/health` |
| `adminer` *(optional profile)* | `adminer` | 8090 | — |

- `depends_on: { postgres: { condition: service_healthy } }` — no race on startup.
- Multi-stage Dockerfile: `maven:3.9-eclipse-temurin-21` builder → `eclipse-temurin:21-jre-alpine` runtime, **Spring Boot layered jars** for cached rebuilds, non-root user, `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75`.
- `initdb.sql` creates both databases; **Flyway** creates tables at service startup.
- Everything configured by environment variables with working defaults; `.env.example` committed, **no secrets in the repo**.
- `SPRING_PROFILES_ACTIVE=demo` by default so the retry schedule is video-friendly (§9.2).

Scaling proof for the demo: `docker compose up --scale delivery-service=2` — `SKIP LOCKED` means no coordination needed, and `worker_id` in the attempt history shows both instances doing work on the same event stream.

---

## 15. How Every Acceptance Criterion Is Satisfied

**AC1 — Successful delivery.** `POST /events` commits `webhook_event` + `outbox_dispatch` in one transaction and returns `201`. The dispatcher claims the outbox row (`SKIP LOCKED`), POSTs to delivery-service, which inserts a `delivery` with `next_attempt_at = now()`. The scheduler claims it (`PENDING → IN_FLIGHT`, `attempt_count = 1`, lease set), the executor POSTs to the receiver outside any transaction, `ResponseClassifier` returns `SUCCESS`, and one transaction writes attempt #1 and transitions to `SUCCEEDED`. `GET /events/evt_123` shows it. — `SuccessfulDeliveryTest`

**AC2 — Temporary failure and retry.** Receiver set to `FAIL_N_THEN_OK(2, 503)`. `503` is in the retryable table, so attempt #1 is recorded with `outcome=RETRYABLE_FAILURE, httpStatus=503, nextAttemptAt=now+5s` and the delivery moves to `RETRY_SCHEDULED`. Once `next_attempt_at` passes, the same scheduler query claims it again — no separate retry machinery exists. Attempt #2 repeats; attempt #3 succeeds. Three ordered attempt rows, final `SUCCEEDED`. — `RetryThenSuccessTest`

**AC3 — Bounded failure.** Receiver `ALWAYS_FAIL(500)`. On attempt 5, `attempt_count == max_attempts`, so the state machine's only legal edge from `IN_FLIGHT` is `FAILED_EXHAUSTED`; `terminal_reason = ATTEMPTS_EXHAUSTED`, `completed_at` set, `next_attempt_at` cleared. The claim query filters on `status IN ('PENDING','RETRY_SCHEDULED')`, so a terminal row is **structurally invisible to the scheduler forever** — attempts cannot continue even in principle. The failure is visible via the API and the dead-letter view. Because `attempt_count` increments at claim time, the bound also survives crashes. — `AttemptExhaustionTest`

**AC4 — Idempotent ingestion.** `INSERT … ON CONFLICT (event_id) DO NOTHING RETURNING id` decides in one round trip. A duplicate writes no outbox row and creates no second delivery, and returns `200` referencing the **existing `deliveryId`** — the brief's "returns or references the existing logical event and does not schedule a second independent delivery job", satisfied by a unique index rather than by application logic. Under 50 concurrent identical submissions, PostgreSQL serialises the conflicting inserts: exactly one `201`, forty-nine `200`, one event row. — `IdempotentIngestTest`, `ConcurrentIngestTest`

**AC5 — Inspectable history.** `delivery_attempt` is append-only with `UNIQUE(delivery_id, attempt_number)`, exposed ordered by attempt number through `GET /api/v1/events/{eventId}` (composed: event + dispatch + delivery + attempts), `GET /internal/v1/deliveries/{id}/attempts` (direct), structured JSON logs, and Prometheus metrics. Each row retains time, attempt number, duration, outcome, HTTP status, error class and message, a 1 KB response snippet, `Retry-After`, the next scheduled attempt, and the worker instance that ran it. — `EventHistoryApiTest`

---

## 16. Decisions the Brief Requires Documented

| Question | Answer |
|---|---|
| Which responses/errors are retryable | §9.1: `2xx` success; `408/425/429/500/502/503/504` plus connect/read timeout, refused, DNS, reset are retryable; **all other `4xx`**, `3xx`, `1xx`, `501`, `505`, TLS and malformed-URL are permanent |
| Retry limit and backoff | 5 total attempts; `min(5s × 5^(n-1), 10min)` with ±20% jitter → 5 s / 25 s / 125 s / 600 s; `Retry-After` honoured and clamped; `demo` and `test` profiles shorten it |
| Delivery guarantee | At-least-once, per-delivery ordered, bounded at 5. Stable `X-Idempotency-Key` for receiver-side dedupe |
| Concurrent duplicate submissions | `INSERT … ON CONFLICT DO NOTHING RETURNING` — the database arbitrates; no application lock exists |
| Information retained per attempt | attempt number, start/end, duration, outcome, HTTP status, error class + message, 1 KB response snippet, `Retry-After`, next scheduled attempt, worker id |
| How to operate with many workers | Run N delivery instances; `SKIP LOCKED` needs no coordination. Scaling limits and the move to `LISTEN/NOTIFY` (removing poll latency) are covered in `SUBMISSION.md` |
| Preventing one endpoint consuming all capacity | Bounded executor + `maxPerRoute = 10` + `EndpointHealthGate` + exponential backoff. Per-endpoint fair-share claiming is the documented next step if multiple endpoints were ever in scope |

---

## 17. Deliberately Not Built

Authentication, multi-tenancy, dashboard UI, multiple subscriber endpoints, rate limits/quotas, distributed queues, multi-region, load testing, secret management — all explicitly out of scope in the brief. HMAC signing is implemented but **off by default** (marked optional/secondary). Manual replay is included because it costs ~20 lines and makes the dead-letter view actionable.

---

## 18. Implementation Plan — Step by Step

Each step is independently reviewable and leaves the build green.

| # | Step | Deliverable | Exit criterion |
|---:|---|---|---|
| 1 | **Skeleton** | Parent POM, 4 modules, Java 21, Spring Boot BOM, Testcontainers BOM, `ClockConfig` | `mvn clean verify` passes on an empty build |
| 2 | **Test receiver** | `POST /receive`, `/control/mode`, `/control/received`, `/control/reset` | Manually drivable with `curl`; needed by everything downstream |
| 3 | **Delivery domain (pure)** | `DeliveryStatus`, `DeliveryStateMachine`, `ResponseClassifier`, `BackoffPolicy`, `Delivery` intent methods | `DeliveryStateMachineTest`, `ResponseClassifierTest`, `BackoffPolicyTest` green — **the retry policy is proven before any I/O exists** |
| 4 | **Delivery persistence** | Flyway `V1__delivery_schema.sql`, entities, repositories with the native `ON CONFLICT` insert and `SKIP LOCKED` claim | `@DataJpaTest` + Testcontainers: `SkipLockedClaimTest`, `AttemptUniquenessTest`, `OptimisticLockingTest` green |
| 5 | **Delivery creation API** | `POST /internal/v1/deliveries`, `GET` read endpoints | Idempotent creation proven: second POST → `200`, same id |
| 6 | **Scheduler + executor + HTTP client** | `DeliveryScheduler`, `DeliveryExecutor`, `WebhookHttpClient`, `ExecutorConfig` (sync under `test`) | **AC1 green** (`SuccessfulDeliveryTest`) |
| 7 | **Retry & termination** | Outcome transaction wiring, `next_attempt_at`, terminal reasons, `Retry-After` | **AC2 + AC3 green** (`RetryThenSuccessTest`, `AttemptExhaustionTest`, `PermanentFailureTest`) |
| 8 | **Lease reaper + startup sweep** | `LeaseReaper`, `ApplicationReadyEvent` hook | `CrashRecoveryTest` green — both the re-queue and the reap-to-exhausted paths |
| 9 | **Ingest service** | Flyway `V1__ingest_schema.sql`, `ON CONFLICT` insert, `POST /api/v1/events`, `@RestControllerAdvice` | **AC4 green** (`IdempotentIngestTest`, `ConcurrentIngestTest`, `PayloadMismatchTest`) |
| 10 | **Outbox dispatcher** | `OutboxDispatcher` + `DeliveryServiceClient` (`RestClient`), bounded backoff | `OutboxRecoveryTest` green — zero loss with delivery-service down |
| 11 | **Composed read API** | `GET /api/v1/events/{eventId}` + graceful degradation | **AC5 green** (`EventHistoryApiTest`, `DeliveryServiceDownReadTest`) |
| 12 | **Endpoint health gate** | `EndpointHealthGate` + its unit test | Gated attempts recorded with `CIRCUIT_OPEN` |
| 13 | **Observability** | JSON logging, MDC filter + task decorator, Micrometer metrics | Metrics visible at `/actuator/prometheus` |
| 14 | **Docker Compose** | Compose file, Dockerfile, `initdb.sql`, `.env.example`, `Makefile` | `docker compose up --build` → all ACs reproducible by `curl` in under 10 minutes |
| 15 | **Docs + demo** | `README.md` (setup, policy tables, semantics), `SUBMISSION.md` (every template section), demo script | Template fully completed; demo covers all five checklist items |

Steps 3–8 build the hard part first and prove it with tests before any service wiring exists. Steps 9–11 close the acceptance criteria. Steps 12–15 are polish and evidence.

---

## 19. Risks & Mitigations for `SUBMISSION.md`

| Risk | Mitigation |
|---|---|
| Polling adds up to 200 ms latency vs. a push queue | Stated explicitly; `LISTEN/NOTIFY` named as the first optimisation. At this scale, 200 ms is irrelevant next to a 5 s first backoff. |
| Two services could read as overbuilt at this size | `SUBMISSION.md` states the availability-profile argument and openly names the single-service alternative. Reviewers reward an honest trade-off over a pretended obvious choice. |
| The dispatcher holds a transaction across an HTTP call | Called out in §6 with the scoping constraints and the change to make if it ever became hot. |
| `EndpointHealthGate` is per-instance | Documented limitation; acceptable with one endpoint and a hard attempt bound. |
| "Explain any part of the submission" follow-up | Every non-obvious mechanism — outbox, `SKIP LOCKED` claim, lease + reaper, `ON CONFLICT` idempotency, claim-time attempt increment — carries a one-sentence rationale here to rehearse. |

---

## 20. Challenge Submission Checklist (from `README.md` / `REVIEW_SCORECARD.md`)

- [x] Runnable source code, no secrets committed — `.env` is gitignored; `.env.example` holds a local throwaway password only
- [x] `SUBMISSION.md` completed from `SUBMISSION_TEMPLATE.md` — every section, bar the three below
- [ ] **Demo video link (3–5 min) near the top of `SUBMISSION.md`** — candidate only
- [x] Focused tests: at least one success path, one failure/recovery path — 879 tests, all five ACs plus crash recovery, zero-loss handoff and degraded reads
- [x] Setup instructions a reviewer can follow in ~10 minutes — `make up && make demo`; first build ~8 min, and `README.md` says so
- [ ] **AI usage disclosed** — candidate only
- [ ] **Credibility note** (prior shipped system, personal contribution, scale, a hard decision) — candidate only
- [x] Demo shows: service + receiver running · successful delivery · receiver failure + retry · attempt history · repeated submission handled idempotently — `scripts/demo.sh` walks all five, transcript verified against containers
- [x] `SUBMISSION.md` answers: what can still cause a duplicate delivery · operating with many workers · preventing one endpoint consuming capacity · production metrics and alerts

**Outstanding for the candidate:** the demo video, the AI-usage disclosure and the credibility
note, each marked `TODO` in `SUBMISSION.md`. One further gap is recorded honestly under
*Limitations*: `docker compose up` could not be executed in the build environment (Compose was
not installable there), though every layer beneath it is verified.

---

## 21. Deviations From the Approved Design

Recorded as they happen, per the house rules. Each one also belongs in `SUBMISSION.md`.

### D1 — `ErrorClass.MALFORMED_URL` added (Step 3)

**Design said:** `ErrorClass` has seven values (§4.2), and §9.1 classifies a malformed target URL as a *permanent* failure.

**Problem:** those two statements were inconsistent. With only the seven original values, a malformed URL could only be recorded as `UNKNOWN` — and `UNKNOWN` has to be **retryable**, because an unrecognised transport fault is far more often transient than not, and losing an event costs more than four wasted calls against a bounded budget. So a misconfigured endpoint would have been retried five times, contradicting the documented policy.

**Change:** added an eighth value, `MALFORMED_URL`, classified `PERMANENT_FAILURE`. One line, and it makes §9.1 implementable exactly as written.

**Not changed:** connection reset still maps to `UNKNOWN`. It needs no value of its own because it carries no distinct *behaviour* — it is retryable, like `UNKNOWN`, and the message field carries the detail. `MALFORMED_URL` earned its place by changing what the engine does; a diagnostic-only value would not have.

### D2 — the reaper reclaims through the entity, not a bulk `UPDATE` (Step 4)

**Design said:** §5.3 specifies crash recovery as a single bulk statement — `UPDATE delivery SET status = 'RETRY_SCHEDULED', next_attempt_at = now() ... WHERE status = 'IN_FLIGHT' AND lease_expires_at < now()`, with a note that deliveries whose budget is already spent go to `FAILED_EXHAUSTED` instead.

**Problem:** that conflicts with invariant 10 — every state change goes through `Delivery.transitionTo()`. A bulk `UPDATE` bypasses the state machine entirely, and the "or `FAILED_EXHAUSTED` if the budget is spent" rule would then exist twice: once in `Delivery.reapExpiredLease()` and again as a `CASE` expression in SQL. Two copies of the attempt bound is exactly the drift the invariant exists to prevent, and the SQL copy is the one no unit test covers.

**Change:** `DeliveryRepository.lockExpiredLeaseIds` selects the expired ids with `FOR UPDATE SKIP LOCKED`; the reaper then loads those deliveries and calls the already-tested `reapExpiredLease()` on each. `SKIP LOCKED` matters here for a second reason — a delivery another instance is actively finishing must be passed over, never waited for.

**Cost:** one extra round trip and a batch of entity updates instead of one statement. Reaping only happens after a crash and is bounded by a batch limit, so the volume is negligible; the exhaustion rule staying in one place is worth more.

### D3 — tests accept an external database via `TEST_DB_URL` (Step 5)

**Design said:** §12.3 and §12.4 specify Testcontainers, and only Testcontainers, for every database-backed test.

**Problem:** Testcontainers requires a Docker socket the current account can open. That is true of a reviewer's machine and of most CI, but not of all of either — CI runners frequently offer PostgreSQL as a service rather than a Docker daemon, and a developer whose account is not in the `docker` group has no way to run two thirds of the suite.

**Change:** `TestDatabase.bind()` is the single place any test binds a datasource. With `TEST_DB_URL` unset it starts the `postgres:16-alpine` container exactly as before; with it set, it points at that server instead. The container class is referenced only from inside a holder class, so an external-database run never loads Testcontainers at all.

**Why it is not a weakening:** the escape hatch changes who starts PostgreSQL, not what is tested. Same migrations, same schema, same assertions, same major version. The one thing it must never become is a route to an embedded database — `SKIP LOCKED`, `ON CONFLICT DO NOTHING` and partial indexes are the behaviours under test, so a non-PostgreSQL target would make the suite evidence about the wrong database. That is stated in the class Javadoc where someone tempted to point it at H2 will read it.

**Default is unchanged.** `./mvnw clean verify` on a machine with Docker behaves exactly as §12 describes, and that is the only path `README.md` documents for reviewers.

### D3 — `Delivery.releaseUnstartedClaim`, the one place an attempt is refunded (Step 6)

**Design said:** §5.4 specifies that when the executor refuses a task, the rejection handler "releases the lease". Invariant 8 says the attempt count rises in the claim transaction so the bound survives a crash.

**Problem:** those two need reconciling. If a rejected task merely gave back the *lease* and kept the spent attempt, overload would consume delivery budgets for requests that were never sent — events failing with `ATTEMPTS_EXHAUSTED` having never reached the receiver once. But refunding in general is exactly what invariant 8 forbids, because after a crash we cannot know whether the request went out.

**Change:** a narrow domain method, `releaseUnstartedClaim`, valid only from `IN_FLIGHT` and used only by the scheduler's rejection path — where the task provably never ran, so no request can have been made. It decrements `attempt_count` and makes the delivery due immediately. Everywhere else a spent attempt stays spent; `reapExpiredLease` in particular still refunds nothing, because a vanished worker may well have been mid-request.

**Why the refund cannot loop:** the scheduler claims no more than the executor's free capacity, so rejection is a race on an estimate rather than a state the system can sit in. The alternative considered was `CallerRunsPolicy`, which needs no refund at all because the scheduler thread runs the task itself; it was rejected because it hides saturation instead of logging it, and stalls the poll loop for the length of a response timeout.

### N1 — the receiver's executable jar carries a `boot` classifier (Step 6, not a design change)

`webhook-delivery-service` now test-depends on `webhook-test-receiver`, because the acceptance tests start the real receiver rather than a mock (§12.4). A repackaged Spring Boot jar keeps its classes under `BOOT-INF/` and cannot be compiled against, so the receiver's executable artifact is now `webhook-test-receiver-<version>-boot.jar` and the plain library jar is the main one. **§14's Docker build must run the `-boot` jar for that service.**

### D4 — the `test` profile keeps the production backoff schedule (Step 7)

**Design said:** §9.2 gives the `test` profile `base = 0`, multiplier n/a, `max = 0` — "immediate; time is driven by the test's `Clock`".

**Problem:** with every delay at zero there is no backoff to observe, so the acceptance tests could show that a retry *happens* but not that it waits, grows, or gets capped. AC2 asks for the retry policy to be demonstrated, and a schedule of all zeroes demonstrates a different policy from the one that ships.

**Change:** the `test` profile now inherits `base-delay`, `multiplier` and `max-delay` from the default profile and overrides only `jitter-factor: 0`. `RetryThenSuccessTest` and `AttemptExhaustionTest` therefore assert the real shipped schedule — 5s, 25s, 125s, then 600s rather than 625s, showing the cap — and the fifth attempt scheduling nothing at all.

**Why it costs nothing:** the clock is a `MutableTestClock`, so stepping over a ten-minute backoff is a field assignment. The whole delivery-service suite still runs in about 31 seconds, and no test sleeps or polls.

### D5 — `ProtocolException` classified as `MALFORMED_URL` in the HTTP adapter (Step 7)

**Design said:** §9.1 lists a malformed URL as permanent. Step 3's D1 added the `MALFORMED_URL` error class for it, mapping `URISyntaxException` and `IllegalArgumentException` in the pure classifier.

**Problem:** those are not the exceptions that actually occur. A target with no scheme reaches Apache HttpClient as `ProtocolException: Target host is not specified`, wrapped by Spring as a `ResourceAccessException` — so it fell through to `UNKNOWN`, which is *retryable*, and a misconfigured endpoint was attempted all five times. The documented policy said one thing and the code did another; only an end-to-end test with a genuinely broken URL surfaced it.

**Change:** `WebhookHttpClient.classify` now recognises `ProtocolException` and `ClientProtocolException` as `MALFORMED_URL`. It belongs in the adapter rather than the domain classifier for the same reason `ConnectTimeoutException` does: these are the HTTP client's own types, and letting them into `ResponseClassifier` would put a library dependency in the one class that has none.

**Safe because:** redirects and automatic retries are both disabled on the client, so the only way a plain POST produces a protocol error is a target it cannot build a request from.

### N2 — `payload_hash` is `VARCHAR(64)`, not `CHAR(64)` (Step 9, not a design change)

§4.1 specifies `char(64)`. PostgreSQL's `char(n)` is blank-padded: a short value is silently widened to length rather than rejected, and it has no storage or speed advantage over `varchar` — the PostgreSQL manual says as much. The column is now `VARCHAR(64)` with `CHECK (payload_hash ~ '^[0-9a-f]{64}$')`, which is strictly stronger: a malformed hash fails instead of being padded into looking valid.

Found by `ddl-auto: validate`, which refused to start the context because the entity mapped `varchar(64)` and the migration said `bpchar` — the schema-agreement check earning its place for the second time.

### N3 — structured logging via Spring Boot rather than `logstash-logback-encoder` (Step 13, not a design change)

§11 names `logstash-logback-encoder`. Spring Boot 3.4 added structured logging with a `logstash` format built in, so `logging.structured.format.console: logstash` produces the same Logstash JSON — MDC included automatically — with no new dependency and no `logback-spring.xml`. Given `CLAUDE.md`'s standing "do not add to the stack", the built-in is the better way to get exactly what §11 asked for. A verified line from a real delivery attempt:

```json
{"@timestamp":"2026-09-21T11:50:40.066+05:30","@version":"1",
 "message":"Attempt 1/5 -> SUCCESS status=200 state=SUCCEEDED nextAttemptAt=null",
 "logger_name":"com.caygnus.webhook.delivery.application.DeliveryOutcomeRecorder",
 "thread_name":"delivery-8","level":"INFO","level_value":20000,
 "attemptNumber":"1","eventId":"evt_e2e",
 "workerId":"ginger-ThinkPad-T460s:1550920:da207ed4",
 "deliveryId":"4ea724e2-2b74-428d-b63a-50fd2122aae5"}
```
