# Product Engineering Challenge Submission

## Candidate

- **Name:** Saisrijan Reddy
- **Email:** srijansai166@gmail.com
- **GitHub:** https://github.com/Saisrijan166/product-engineer-ps.git
- **Selected problem:** Problem 2 — Webhook Retry Engine
- **Demo video:** https://drive.google.com/file/d/1NkIm9qpeEIggdO3svRv80sT3trMHlbMY/view?usp=sharing

---

## Run the project

**Prerequisites:** Docker with Compose, and nothing else. No JDK, no Maven, no accounts, no API
keys, no paid services.

```bash
git clone https://github.com/Saisrijan166/product-engineer-ps.git
cd product-engineer-ps

make up          # docker compose up --build -d --wait
make demo        # narrates all five acceptance criteria
make verify      # asserts them; exits non-zero if any regress
```

The first `make up` builds three images from source and takes about 8 minutes, nearly all of it
resolving Maven dependencies; later starts take seconds. `make down` stops everything and drops
the volume. `make logs` follows all services. `make ps` shows health.

Once up:

| | |
| --- | --- |
| Browser demo | <http://localhost:8080> — optional, see below |
| Ingest API | <http://localhost:8080/api/v1/events> |
| Delivery API | <http://localhost:8081/internal/v1/deliveries> |
| Test receiver | <http://localhost:8082/control/received> |
| PostgreSQL | `localhost:55433`, database `webhook_delivery` |

### Environment variables

Every one has a working default, so nothing needs setting to run the project. Copy
`deploy/docker/.env.example` to `deploy/docker/.env` to change any of them — that file is
gitignored and holds no secrets worth protecting; the database credentials are for a throwaway
local container.

| Variable | Default | What it does |
| --- | --- | --- |
| `INGEST_PORT` | `8080` | Host port for the ingest service and the browser demo |
| `DELIVERY_PORT` | `8081` | Host port for the delivery service |
| `RECEIVER_PORT` | `8082` | Host port for the test receiver |
| `POSTGRES_PORT` | `55433` | Host port for PostgreSQL — not 5432, so it never fights a local install |
| `ADMINER_PORT` | `8090` | Only with `--profile tools` |
| `POSTGRES_USER` | `webhook` | Throwaway container credential |
| `POSTGRES_PASSWORD` | `webhook` | Throwaway container credential |
| `SPRING_PROFILES_ACTIVE` | `demo` | `demo` backs off 1s → 2s → 4s → 8s; `default` is 5s → 25s → 125s → 600s |

Compose derives the rest from those: `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD`,
`WEBHOOK_DELIVERY_TARGET_URL` (where deliveries are sent),
`WEBHOOK_DELIVERY_SERVICE_BASE_URL` (how ingest reaches delivery), and
`UI_DELIVERY_BASE_URL` / `UI_RECEIVER_BASE_URL` (what a *browser on the host* can reach, which is
not the same as the Compose network names). Running outside Docker instead, each service reads
`INGEST_DB_URL` / `INGEST_DB_USER` / `INGEST_DB_PASSWORD`, `DELIVERY_DB_URL` / `DELIVERY_DB_USER` /
`DELIVERY_DB_PASSWORD`, `DELIVERY_SERVICE_URL`, `WEBHOOK_TARGET_URL` and `SERVER_PORT`.

### The successful scenario

```bash
# 1. the receiver answers 200 to everything
curl -X POST http://localhost:8082/control/mode \
  -H 'Content-Type: application/json' -d '{"mode":"ALWAYS_OK"}'

# 2. submit an event
curl -X POST http://localhost:8080/api/v1/events \
  -H 'Content-Type: application/json' \
  -d '{"eventId":"evt_success_1","type":"incident.created",
       "occurredAt":"2026-09-15T10:00:00Z",
       "payload":{"incidentId":"inc_456","severity":"high"}}'
# -> 201 Created

# 3. read the state and the attempt history
curl -s http://localhost:8080/api/v1/events/evt_success_1 | jq .
# -> delivery.status SUCCEEDED, attemptCount 1, one attempt with outcome SUCCESS
```

Submitting step 2 **again, unchanged** returns `200` rather than `201`, references the same
`deliveryId`, and schedules no second delivery. Submitting the same `eventId` with a *different*
payload returns `409`.

### The failure and recovery scenario

```bash
# the receiver fails twice with 503, then recovers
curl -X POST http://localhost:8082/control/mode \
  -H 'Content-Type: application/json' \
  -d '{"mode":"FAIL_N_THEN_OK","failCount":2,"status":503}'

curl -X POST http://localhost:8080/api/v1/events \
  -H 'Content-Type: application/json' \
  -d '{"eventId":"evt_retry_1","type":"incident.created",
       "occurredAt":"2026-09-15T10:00:00Z","payload":{"incidentId":"inc_888"}}'

sleep 10
curl -s http://localhost:8080/api/v1/events/evt_retry_1 \
  | jq '.delivery | {status, attemptCount, attempts: [.attempts[] | {attemptNumber, outcome, httpStatus}]}'
# -> SUCCEEDED after 3 attempts: RETRYABLE_FAILURE 503, RETRYABLE_FAILURE 503, SUCCESS 200
```

For the **bounded** case, use `{"mode":"ALWAYS_FAIL","status":500}` and wait ~20s: exactly five
attempts, then `FAILED_EXHAUSTED` with `terminalReason: ATTEMPTS_EXHAUSTED`, and it stays there.
It then appears in the dead-letter view at
`GET http://localhost:8081/internal/v1/deliveries?status=FAILED_EXHAUSTED`.

Receiver modes are `ALWAYS_OK`, `ALWAYS_FAIL`, `FAIL_N_THEN_OK` and `TIMEOUT`, each accepting
`status`, `failCount`, `delayMs` and `retryAfterSeconds`. `POST /control/reset` clears it.

### A browser demo (optional, and outside the brief)

<http://localhost:8080> serves a page that runs all five criteria from buttons and draws the
attempt timeline to scale, so the exponential backoff is visible as geometry. It is written for a
non-technical reader — the five-step diagram narrates itself in plain English and the attempt
history reads as a story, with status codes and timings kept behind a *technical details* toggle
that is closed by default. The brief lists a dashboard as out of scope, so this is not offered as
graded work — see *Limitations*. Every action on it shows the `curl` it just ran.

---

## Run the tests

```bash
make test        # ./mvnw clean verify
```

**879 tests, ~2 minutes.** Needs a Docker socket; Testcontainers starts its own PostgreSQL 16.
No paid service. [docs/LOCAL_DEV.md](docs/LOCAL_DEV.md) has a no-Docker, no-root fallback for
machines where the account cannot open the socket.

| Covering | Suite | Tests |
|---|---|---:|
| **AC1** successful delivery | `SuccessfulDeliveryTest` | 11 |
| **AC2** temporary failure, then retry | `RetryThenSuccessTest` | 8 |
| **AC3** bounded failure | `AttemptExhaustionTest` | 7 |
| **AC4** idempotent ingestion | `IdempotentIngestTest`, `ConcurrentIngestTest` | 12 |
| **AC5** inspectable history | `EventHistoryApiTest` | 8 |
| Crash recovery, both branches | `CrashRecoveryTest` | 11 |
| Zero-loss handoff | `OutboxRecoveryTest` | 14 |
| Degraded read | `DeliveryServiceDownReadTest` | 6 |
| Transport failures | `TransportFailureRetryTest` | 7 |
| Permanent failures | `PermanentFailureTest` | 15 |
| Endpoint gating | `CircuitOpenDeliveryTest`, `EndpointHealthGateTest` | 24 |
| Retry policy, pure | `ResponseClassifierTest` | 548 |
| State machine, full 6×6 matrix | `DeliveryStateMachineTest` | 84 |
| Persistence behaviours | `SkipLockedClaimTest` + 4 others | 31 |

**Nothing in the suite sleeps or polls** — no `Thread.sleep`, no Awaitility, verified by grep.
Time is an injected, mutable `Clock`; the scheduler is ticked by name; the worker pool runs on the
calling thread under the `test` profile, so `scheduler.runOnce()` returns only once every delivery
it claimed has finished. A test that needs to be ten minutes into a backoff says
`CLOCK.advance(...)` and asserts immediately.

Tests are **never** run against H2. `FOR UPDATE SKIP LOCKED`, `ON CONFLICT DO NOTHING` and partial
indexes are the behaviours under test; an embedded database would make a green suite evidence
about the wrong database.

---

## Architecture and data flow

```
 POST /api/v1/events
        │
        ▼
 ┌──────────────────────────────────────┐
 │ ingest-service                       │   TX ── one transaction, or neither row
 │   INSERT webhook_event ON CONFLICT   │◄─┐
 │   INSERT outbox_dispatch             │──┘
 └──────────────────┬───────────────────┘
                    │  OutboxDispatcher @500ms
                    │  RestClient POST, Idempotency-Key: {eventId}
                    ▼
 ┌──────────────────────────────────────┐
 │ delivery-service                     │
 │   INSERT delivery ON CONFLICT        │   one delivery per event, enforced
 │                                      │
 │   DeliveryScheduler @200ms           │
 │     SELECT … FOR UPDATE SKIP LOCKED  │   TX1 ── claim, attempt_count++, lease
 │        ↓                             │
 │     DeliveryExecutor                 │
 │       EndpointHealthGate             │
 │       RestClient POST ───────────────┼──▶ receiver
 │       ResponseClassifier             │
 │        ↓                             │   TX2 ── attempt row + state transition
 │   LeaseReaper @10s + at startup      │
 └──────────────────────────────────────┘
```

**Four responsibilities, four collaborators.** Ingestion accepts and retains. The dispatcher hands
over. The scheduler decides what is due. The executor makes the call and records what happened.
Each owns one transaction boundary, and none reaches into another's.

**The queue is a table.** `delivery.next_attempt_at` plus `SELECT … FOR UPDATE SKIP LOCKED`.
Several instances poll the same partial index and come away with disjoint sets, with no
coordinator, no lease service and no distributed lock. A tick claims only what its worker pool can
start, so the backlog stays in PostgreSQL — visible to every instance, and still there after a
restart.

**Retry is not separate machinery.** A retryable failure writes `next_attempt_at = now + backoff`
and the same claim query picks it up when the time comes. There is no retry queue, no delay topic
and no scheduler-of-schedulers.

**State transitions go through one place.** `Delivery.transitionTo()` checks an
`EnumMap<DeliveryStatus, Set<DeliveryStatus>>`; there is no public `setStatus`. The three terminal
states have no outgoing edges, and the claim query selects only `PENDING` and `RETRY_SCHEDULED` —
so a finished delivery is **structurally invisible** to the scheduler. That, rather than a
carefully-checked counter, is what makes "attempts do not continue forever" true.

**The attempt count rises before the HTTP call, in the claim transaction.** A process that dies
mid-attempt has already spent it, so the bound survives a crash rather than resetting with it.

Full design, including the state machine and every deviation from it, in
[ARCHITECTURE.md](ARCHITECTURE.md).

---

## Technology choices

**Java 21, Spring Boot, PostgreSQL, Docker.** Chosen because the interesting parts of this problem
are transactional — idempotent acceptance, a durable queue, crash recovery — and PostgreSQL does
all three well enough that no second piece of infrastructure is needed. `FOR UPDATE SKIP LOCKED`
and `ON CONFLICT DO NOTHING` are not optimisations here; they *are* the concurrency control and
the idempotency.

### What I removed, and why

The first design used **Kafka, Redis and Kubernetes**. All three were cut before any code was
written ([ARCHITECTURE.md §0](ARCHITECTURE.md)):

| Removed | Replaced by | Why the replacement is better *here* |
|---|---|---|
| Kafka delay topics | `next_attempt_at` + polling claim | Backoff becomes a timestamp comparison, so it is exact rather than bucketed into tiers. One scheduler instead of two. |
| Kafka as transport | Transactional outbox + `RestClient` | Same zero-loss property. The outbox was always what provided it; Kafka was never the durable part. |
| Redis ingest lock | `INSERT … ON CONFLICT DO NOTHING` | Strictly better: one round trip, no rolled-back transactions under a concurrent burst, no exception-driven control flow, and no "what if Redis is down" path to reason about. |
| Redis delivery lock | `SKIP LOCKED` + lease + `@Version` | The claim becomes atomic in the store of record. |
| Kubernetes | Docker Compose | ~15 files the scorecard gives no credit for, and a longer path to a running system. |

Net effect: fewer moving parts *and* a stronger correctness story.

### The honest trade-off: this would be smaller as one service

**A single Spring Boot service would be perhaps 15% less code and noticeably less ceremony.** No
`webhook-common`, no HTTP hop, no outbox — ingestion could call the scheduler directly, and the
composed read would be a join. If I were building this for myself, that is what I would write.

Two services is still the right answer for *this* brief, for one reason: **the two halves have
opposite availability requirements, and the difference matters exactly when things go wrong.**
Ingestion must keep accepting events precisely when the receiver is down — that is the entire
point of the product. Delivery is slow, I/O-bound, restart-prone and needs its own thread budget
and its own blast radius. Deploying them together means a delivery-service problem can refuse a
customer's event, which is the one failure mode the engine exists to prevent. The brief also
rewards "separation between event ingestion, scheduling, delivery, and storage" explicitly, and I
would rather make that separation structural than claim it from a package layout.

What the split costs, stated plainly: an HTTP hop with its own failure modes, an outbox to make
that hop lossless, a second database and migration set, a degraded-read path, and a second thing
to deploy. I think that is worth it here. I would not pretend it is free.

### Smaller choices

- **Spring `RestClient` over `WebClient`.** The delivery path is blocking, bounded and one call
  deep. Reactive would add types and lose stack traces for no throughput we need at five
  concurrent attempts.
- **Apache HttpClient 5 under it, but only for the outbound webhook.** It gives a connection pool
  with a per-route cap — one slow receiver can occupy at most ten workers — plus a separate
  connect timeout, and the ability to turn *off* its own retrying. That last one matters: hidden
  retries would produce attempts with no `delivery_attempt` row, silently breaking both the bound
  and the history. The internal ingest→delivery call uses the plain JDK client, because it needs
  none of that.
- **Flyway with `ddl-auto: validate`.** The migration owns the schema and Hibernate checks it
  agrees. This caught two real mismatches during development.
- **No Lombok, no Resilience4j, no Quartz.** A 40-line health gate and a 30-line backoff are
  easier to read than the configuration needed to get a library to do the same thing, and both are
  pure functions with exhaustive tests.

---

## Important decisions

### 1. Idempotency is a database constraint, not a code path

There is no lock anywhere in the ingest path — not a row lock taken by the application, not a
table, not a distributed one. `INSERT … ON CONFLICT (event_id) DO NOTHING` answers "have we seen
this?" in the same statement that accepts it.

The obvious alternative — check whether the row exists, and insert it if not — passes every
sequential test and falls over the moment two requests arrive together. `ConcurrentIngestTest`
fires **50 simultaneous submissions of one event** and asserts exactly one `201`, forty-nine
`200`, one event row, one outbox row, and `duplicate_submission_count = 49`. The counter is
incremented by PostgreSQL against a row it holds a lock on; reading it into Java and adding one
would lose ~48 of those, invisibly, because the count is only ever inspected.

The same statement does double duty: the payload hash is in the `WHERE` clause, so comparing and
counting are one atomic operation with no window between them.

### 2. Retryability is one pure function, and retrying is a whitelist

`ResponseClassifier` is plain Java — no Spring, no JPA, no I/O — and 548 parameterised tests sweep
every status from 100 to 599 against a table restated by hand, so a code moving in or out of the
retryable set fails the build rather than quietly changing behaviour.

Only seven statuses are retryable. "Everything that is not a 2xx" would retry a `422` five times
over twelve minutes to be told the same thing each time, burning a worker and a connection on each
attempt and delaying the deliveries that could have succeeded.

The cost of getting this wrong runs both ways, which is why the transport cases get the same
care: treat a refused connection as permanent and a receiver's ten-second restart loses every
event in it; treat a bad certificate as retryable and four more attempts go out to establish what
the first already did.

### 3. The attempt count rises before the call, and the lease makes a crash visible

Claim → commit → call → record → commit. The attempt is spent in the claim transaction, so a
worker killed mid-flight has already used it. That worker leaves a row marked `IN_FLIGHT` holding
a lease nobody is honouring — invisible to the claim query, because the same filter that stops
finished deliveries being retried also hides abandoned ones. `LeaseReaper` is the component whose
only job is to notice, and it runs at startup as well as on a timer: the common case is an
instance dying and coming back, and the deliveries it abandoned are the ones most likely waiting
on it.

The attempt is **not** refunded when a lease is reaped. The dead worker may well have reached the
receiver; counting it is what keeps the bound honest across a crash.

---

## Assumptions and limitations

### Assumptions

- **One receiver, configured once.** Per the brief. `target_url` is nonetheless snapshotted onto
  each delivery rather than read at attempt time, so history stays truthful if configuration
  changes.
- **`eventId` is stable and caller-supplied.** Reusing one with a different payload is a caller
  bug and returns `409` rather than being absorbed. Silently ignoring the new content or silently
  replacing an event already on its way out both seemed worse than refusing.
- **Payloads are JSON objects up to 256 KB**, rejected before anything is persisted.
- **`Retry-After` is honoured in delta-seconds form only.** The HTTP-date form is not parsed:
  doing it correctly means trusting the receiver's clock against ours, and being wrong schedules
  an attempt at the wrong time rather than failing visibly.

### Limitations

- **The payload is not byte-preserved.** It round-trips through a `jsonb` column, which normalises
  whitespace and key order. The receiver gets an equivalent document, not identical bytes. Fine
  here because the optional HMAC is over our own body; it would matter if we ever forwarded a
  third party's signature.
- **`EndpointHealthGate` is per-instance.** Three instances means three independent opinions about
  the same endpoint, each needing its own ten failures; a restart forgets. It was Redis in v1, and
  dropping Redis is what made it local. Acceptable because the gate is an *optimisation* — nothing
  about correctness depends on it — and the attempt bound already caps the damage.
- **A gated attempt still spends one from the budget.** A delivery whose whole remaining budget
  fell inside one open window could exhaust without a request leaving the process. The window
  (30s) is deliberately shorter than the later backoffs (125s, 600s) so this stays a corner case.
- **The outbox dispatcher holds a transaction across an HTTP call** — the one deliberate exception
  to "no network I/O inside a transaction". Bounded at 20 rows × 5s, internal, both timeouts set,
  and not load-bearing: the lock only stops two instances making the same call, while correctness
  comes from `UNIQUE(delivery.event_id)` at the far end. Claim-commit-call-commit is the change to
  make if it ever became hot; it costs a round trip per row and its own lease-and-reaper.
- **Request signing is implemented but off by default.** The brief marks it optional and
  secondary.
- **Verified with Compose.** An earlier draft of this document noted that `docker compose up`
  had never been executed, because Compose could not be installed in the environment this was
  built in. That gap is now closed: `docker compose up --build -d --wait` brings all five
  services healthy, the UI is served, and all five acceptance criteria pass both from
  `scripts/verify-acceptance.sh` (31/31) and from the browser.
- **There is an optional browser UI, and it is explicitly outside the brief.** The problem
  statement lists a management dashboard as out of scope and the challenge README says visual
  polish earns no points, so it is not offered as graded work — it exists because AC5 and the
  retry backoff are hard to appreciate in a terminal and obvious in a picture. It is plain
  HTML/CSS/ES-modules served as static files by the ingest service: no build step, no npm, no
  framework, no CDN, no new dependency, no new container, no new port. It touches no domain,
  application, persistence or scheduling code. Two server-side additions support it, both inert
  under the default profile: a `@Profile("demo")` CORS configuration on the delivery service and
  receiver, and `GET /api/v1/ui-config`, which tells the page which ports to call. Deleting
  `static/`, those two config classes and that controller would remove the UI and change no
  behaviour. If a reviewer would rather it were not there, that deletion is the whole change.
- **The demo UI cannot show delivery panels while scaled.** Several replicas cannot share one
  published port, and publishing a range makes the port unpredictable for a browser. Scaling
  therefore uses an overlay that drops the mapping; `worker_id` in the attempt history still
  shows the split.
- **Deliberately not built:** authentication, multi-tenancy, a dashboard, multiple subscriber
  endpoints, rate limits, multi-region, load testing. All out of scope per the brief.

---

## Production and scale

### What could still cause a receiver to observe a duplicate delivery?

Three things, and all three are inherent to at-least-once rather than defects:

1. **The receiver commits and the response is lost.** A read timeout is indistinguishable here
   from the request never arriving, so we retry. `TransportFailureRetryTest` demonstrates this
   directly: the receiver records the request, we time out, and the retry carries the same
   idempotency key.
2. **A worker dies after the call returns but before the outcome transaction commits.** The lease
   expires, the reaper re-queues, and the delivery goes out again.
3. **A network partition makes a lease expire while the original worker is still blocked in
   `read()`.** The lease is `responseTimeout + 10s`, so the call must already have timed out — but
   a long GC pause or serious clock skew could still do it.

What is *not* a cause: duplicate ingestion (`UNIQUE(event_id)`), duplicate dispatch
(`ON CONFLICT`), a double claim (`SKIP LOCKED`), or a duplicate attempt row
(`UNIQUE(delivery_id, attempt_number)`).

Hence the contract: `X-Idempotency-Key` is the delivery id and is **identical on every attempt**.
Receivers should record it with the effect it produced, in the same transaction as that effect,
and ignore a key they have already seen.

### How would you operate this with many workers?

It already works: run more `delivery-service` instances. `SKIP LOCKED` needs no coordinator, and
`docker compose up --scale delivery-service=2` splits the work with no configuration — verified at
60 deliveries across two instances, 34/26, zero double attempts, with `worker_id` on every attempt
row showing which instance did what.

What I would change as volume grew, roughly in order:

1. **`LISTEN`/`NOTIFY` to remove poll latency.** Today a delivery due now waits up to 200ms. At
   low volume that is irrelevant next to a 5s first backoff; at high volume the polling itself
   becomes the floor on both latency and database load.
2. **Partition or archive completed deliveries.** The partial indexes keep the *queue* fast
   regardless of history size, but the table still grows forever. Move terminal rows older than N
   days to cold storage.
3. **Shard the claim by a hash bucket** if a single `SKIP LOCKED` index becomes a contention point.
   Each instance claims from its own buckets, with rebalancing on membership change. Only worth it
   well past the point where a single PostgreSQL is the bottleneck — and at that point a real
   queue starts to earn its place.
4. **Make the health gate shared.** Per-instance breakers waste N× the failures to form an
   opinion. This is where Redis would come back, and it would be justified then.

### How would you prevent one failing endpoint from consuming all capacity?

Four layers, innermost first:

1. **Per-route connection cap (10) below the worker count (8×N).** One slow host can occupy at
   most ten connections, so it cannot exhaust the pool.
2. **Both timeouts set** — 2s connect, 5s response. A hung receiver releases its worker in five
   seconds, not never.
3. **`EndpointHealthGate`.** After ten consecutive retryable failures the engine stops calling for
   30 seconds, then lets exactly one attempt through as a trial. A receiver that is down stops
   costing worker time almost immediately.
4. **Exponential backoff with the attempt bound.** A doomed delivery makes five calls, not
   unlimited ones.

What is missing for a genuinely multi-tenant engine — out of scope here, since the brief specifies
one endpoint — is **per-endpoint fair-share claiming**. Today a backlog of ten thousand deliveries
for one broken endpoint would be claimed ahead of a healthy endpoint's ten, because the claim
query orders purely by `next_attempt_at`. The fix is to claim round-robin across endpoints
(`DISTINCT ON (target_host)` or a per-endpoint quota in the claim), so no single tenant can
monopolise the queue.

### What metrics and alerts would you add in production?

The meters are already there, at `/actuator/prometheus`:

| Meter | What it tells you |
|---|---|
| `webhook_delivery_attempts_total{outcome}` | throughput and the success/failure mix |
| `webhook_delivery_duration_seconds{outcome}` | tagged by outcome, because a slow success is a slow receiver while a slow failure is a timeout — averaging them hides both |
| `webhook_delivery_terminal_total{reason}` | `succeeded` vs `non_retryable_response` vs `attempts_exhausted` |
| `webhook_delivery_due_backlog` | the real queue depth: past due, unclaimed |
| `webhook_lease_reclaimed_total` | the clearest available proxy for instances dying mid-attempt |
| `webhook_endpoint_gate_state` | 1 while the engine is holding off an endpoint |
| `webhook_outbox_pending` | dispatch intents not yet acknowledged |
| `webhook_ingest_duplicates_total` | AC4's evidence as a number; a climb means a producer started retrying |

Alerts I would actually page on:

- **`webhook_delivery_terminal_total{reason="attempts_exhausted"}` rising.** Events are being lost
  as far as the customer is concerned. The one that matters most.
- **Oldest `PENDING` outbox row older than 60s.** Ingestion is still returning `201` while nothing
  is being delivered — the failure that is invisible from the outside.
- **`webhook_delivery_due_backlog` growing over 5 minutes.** The fleet is behind.
- **`webhook_endpoint_gate_state == 1` for over 5 minutes.** A receiver has been down long enough
  to start costing events.
- **`webhook_lease_reclaimed_total` rate above baseline.** Instances are dying.

Ticket rather than page: `non_retryable_response` climbing (usually a payload or configuration
change), and `webhook_ingest_duplicates_total` spiking (usually harmless, occasionally a runaway
producer).

Logs are one JSON line per event, Logstash format, with `eventId`, `deliveryId`, `attemptNumber`
and `workerId` in the MDC — populated by one servlet filter and one executor task decorator, so
no business code touches the MDC and no path can forget to.

### What I would change first

Two things, before anything on the scaling list:

1. **Ship the demo profile's schedule as configuration, not a profile.** Retry policy is the kind
   of thing an operator needs to change during an incident without a redeploy.
2. **Add an operator-facing replay.** A `FAILED_EXHAUSTED` delivery is currently a dead end unless
   someone calls the optional replay endpoint. A receiver that was down for an hour leaves a pile
   of them, and bulk replay-by-time-window is the first thing anyone would ask for.

---

## AI usage

**Tool used:** Claude Code (Anthropic), running Claude Opus 5. It was used throughout, and
heavily. Treating it as a footnote would misrepresent how this was built.

**What it did**

- **Design.** I described the problem and the constraints; it produced `ARCHITECTURE.md`. The
  first version used Kafka for the retry queue, Redis for idempotency locks and Kubernetes
  manifests. I rejected all three and had it redesign around PostgreSQL alone — that call, and
  the reasoning recorded in §0 of that document, is the one that shaped everything else.
- **Implementation.** It wrote effectively all of the Java, SQL, Docker, scripts and the browser
  UI, working through the fifteen steps in `ARCHITECTURE.md` §18 one at a time, with the build
  green at each boundary.
- **Tests.** It wrote all 879. The determinism approach — injected `Clock`, manually-ticked
  schedulers, a synchronous executor under the `test` profile so no test ever sleeps — came from
  the brief's own warning about tests that depend on arbitrary sleep timing.
- **Documentation.** This file, `README.md`, `ARCHITECTURE.md` and the code comments.

**How I worked with it**

I drove the sequence and made the scope calls: dropping Kafka, Redis and Kubernetes; keeping the
two-service split and accepting the trade-off argued above; ordering the work so the retry policy
was proven by unit tests before any I/O existed; deciding the browser UI was worth building but
had to stay outside the graded scope. I reviewed each step before the next began, ran the build
and the acceptance script myself, and pushed back where the output drifted from the design —
three such deviations are recorded as D1–D3 in `ARCHITECTURE.md` §21 rather than quietly absorbed.

**What it got wrong**

Worth stating, because it is the honest part. A CSS rule that set `display: flex` silently
defeated the `hidden` attribute, so a UI panel could never be closed — found by driving a real
browser, not by reading the code. An actuator CORS gap and a compose port-range choice both
passed every test and only failed in a browser. Two assertions in the acceptance script were
counting lines instead of occurrences and reported false results. None of these were caught by
inspection; all were caught by running the thing.

**What I am responsible for**

All of it. I can explain any part of this codebase: the `SKIP LOCKED` claim and why the attempt
count rises inside it, why idempotency is a unique index rather than a lock, what the lease and
reaper do after a crash, and which of the decisions above I would make differently at a
different scale.

---

## Credibility note

### Portfolio SaaS platform

Live: https://portfoliooss.vercel.app

A multi-user SaaS platform for portfolio management: users manage their portfolio content through
secure APIs and an admin dashboard, with AI-assisted resume-to-portfolio conversion and automated
content publishing.

**My contribution.** I worked across the stack. On the backend: the service architecture and REST
APIs in Java and Spring Boot, authentication and authorisation with OAuth2 and JWT, and
PostgreSQL persistence through JPA and Hibernate. On the frontend: Next.js and React. I also set
up the Docker-based development environment and the integration between the separate components.

**Scale and operational complexity.** It was built as a multi-user platform rather than a
single-user application, which is where most of the difficulty came from: authenticated user
flows with per-user data isolation, persistent PostgreSQL state, separate frontend and backend
deployables communicating over REST, and automated content-processing workflows running outside
the request path. Docker kept development and deployment consistent across those pieces.

**One difficult decision.** I split responsibilities across frontend, backend services,
authentication and the data layer instead of building one tightly coupled application. That cost
real complexity — more API surface to design and keep compatible, more to deploy, and failures
that could now happen between components rather than inside one. I accepted it because the
AI-assisted features were clearly going to keep arriving, and each one would have made a single
coupled codebase harder to change than the last. The same instinct is visible in this submission,
and so is the counter-argument: §*The honest trade-off* above argues that at *this* size the split
is harder to justify, and I have said so rather than pretend the decision is free.

**Evidence.** Listed as a live project on my resume. Stack: Next.js, React, TypeScript, Tailwind
CSS, Zustand, Spring Boot, Java, microservices, REST APIs, OAuth2, JWT, PostgreSQL, JPA,
Hibernate, Docker and Git.
