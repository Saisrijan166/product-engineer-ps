# Webhook Retry Engine

A backend service that accepts an event and delivers it to one configured HTTP endpoint, with
bounded retries, idempotent ingestion, and a delivery history you can read.

Submission for the [Caygnus Product Engineering Challenge](docs/CHALLENGE_README.md), Problem 2.
The original challenge README has moved to `docs/CHALLENGE_README.md`; this file is the one you
want in order to run the thing.

- **[SUBMISSION.md](SUBMISSION.md)** — decisions, trade-offs, limitations, demo video
- **[ARCHITECTURE.md](ARCHITECTURE.md)** — the design this was built from, and every deviation from it
- **[docs/LOCAL_DEV.md](docs/LOCAL_DEV.md)** — running the test suite, including without Docker

---

## Run it

**Prerequisites:** Docker with Compose, and nothing else. No JDK, no Maven, no accounts, no keys.

```bash
git clone <this-repo> && cd product-engineer-ps
make up          # or: docker compose -f deploy/docker/docker-compose.yml up --build -d --wait
make demo        # walks through all five acceptance criteria, narrated
make verify      # asserts the same five and exits non-zero if any regress
```

> The first `make up` builds three images from source and takes **about 8 minutes**, almost all
> of it resolving Maven dependencies. Later starts take seconds. If ports 8080–8082 are taken,
> `cp deploy/docker/.env.example deploy/docker/.env` and change them there.
>
> `make demo` is the one to watch — it narrates what the engine is doing. `make verify` is the
> one to trust: same five criteria, 31 assertions, a non-zero exit if any of them break.

That brings up four containers:

| | | |
|---|---|---|
| **ingest-service** | `localhost:8080` | accepts events, owns idempotency |
| **delivery-service** | `localhost:8081` | claims due work, makes the HTTP call, records attempts |
| **test-receiver** | `localhost:8082` | a webhook target whose failures you control |
| postgres | `localhost:55433` | both services' databases |

`make down` stops everything and drops the volume. `make logs` follows the two services.

---

## A browser demo (optional)

Once the stack is up, **<http://localhost:8080/>** serves a page that drives the whole engine:
a five-step diagram that animates one stage at a time with a plain-English caption and a colour
legend, a form to send a message, controls to make the receiving system misbehave on purpose, the
attempt history written as a story with the waits drawn to scale, and one-click guided runs of all
five acceptance criteria. Every button shows the `curl` it just ran, so the page teaches the API
rather than hiding it.

It is deliberately written for someone who has never met the words *webhook*, *idempotent* or
*backoff*: plain words first, and the status codes, timings and identifiers kept one toggle away
under *technical details* rather than on the surface.

> **This is a demo aid, not a feature.** The brief lists a management dashboard as out of scope
> and says visual polish earns no points, so it is deliberately outside the graded work: plain
> HTML, CSS and ES modules under `webhook-ingest-service/src/main/resources/static/`, no build
> step, no dependency, no extra container. It changes no production code path — deleting that
> directory removes the page and nothing else. The only server-side additions are a
> `@Profile("demo")` CORS config and a `GET /api/v1/ui-config` endpoint that tells the page which
> ports to call, both inert outside the demo profile.

Prefer the terminal? Everything below does the same thing.

---

## The five acceptance criteria, by hand

`make demo` runs all of these. Here they are individually, to paste one at a time.

Throughout: **8080 is ingest**, **8082 is the receiver's control API**. Retries use the `demo`
profile (1s → 2s → 4s → 8s), so nothing below takes more than about fifteen seconds.

### AC1 — successful delivery

```bash
curl -X POST localhost:8082/control/mode -H 'Content-Type: application/json' \
  -d '{"mode":"ALWAYS_OK"}'

curl -X POST localhost:8080/api/v1/events -H 'Content-Type: application/json' -d '{
  "eventId": "evt_ac1", "type": "incident.created",
  "occurredAt": "2026-09-15T10:00:00Z",
  "payload": {"incidentId": "inc_456", "severity": "high"}
}'
# → 201 Created

sleep 2 && curl -s localhost:8080/api/v1/events/evt_ac1 | jq '.delivery | {status, attemptCount}'
# → { "status": "SUCCEEDED", "attemptCount": 1 }
```

### AC2 — temporary failure, then retry

```bash
curl -X POST localhost:8082/control/mode -H 'Content-Type: application/json' \
  -d '{"mode":"FAIL_N_THEN_OK","failCount":2,"status":503}'

curl -X POST localhost:8080/api/v1/events -H 'Content-Type: application/json' \
  -d '{"eventId":"evt_ac2","type":"incident.created","occurredAt":"2026-09-15T10:00:00Z","payload":{"severity":"high"}}'

sleep 10 && curl -s localhost:8080/api/v1/events/evt_ac2 \
  | jq '.delivery.attempts[] | {attemptNumber, outcome, httpStatus}'
# → attempt 1  RETRYABLE_FAILURE  503
#   attempt 2  RETRYABLE_FAILURE  503
#   attempt 3  SUCCESS            200
```

### AC3 — bounded failure

```bash
curl -X POST localhost:8082/control/mode -H 'Content-Type: application/json' \
  -d '{"mode":"ALWAYS_FAIL","status":500}'

curl -X POST localhost:8080/api/v1/events -H 'Content-Type: application/json' \
  -d '{"eventId":"evt_ac3","type":"incident.created","occurredAt":"2026-09-15T10:00:00Z","payload":{"severity":"high"}}'

sleep 20 && curl -s localhost:8080/api/v1/events/evt_ac3 \
  | jq '.delivery | {status, attemptCount, terminalReason}'
# → { "status": "FAILED_EXHAUSTED", "attemptCount": 5, "terminalReason": "ATTEMPTS_EXHAUSTED" }

# It stops there. Wait as long as you like and it stays at five.
curl -s 'localhost:8081/internal/v1/deliveries?status=FAILED_EXHAUSTED' | jq '.count'
```

### AC4 — idempotent ingestion

```bash
curl -X POST localhost:8082/control/mode -H 'Content-Type: application/json' -d '{"mode":"ALWAYS_OK"}'

for i in 1 2 3; do
  curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8080/api/v1/events \
    -H 'Content-Type: application/json' \
    -d '{"eventId":"evt_ac4","type":"incident.created","occurredAt":"2026-09-15T10:00:00Z","payload":{"severity":"high"}}'
done
# → 201, 200, 200

sleep 2 && curl -s localhost:8080/api/v1/events/evt_ac4 \
  | jq '{duplicateSubmissionCount, attempts: (.delivery.attemptCount)}'
# → { "duplicateSubmissionCount": 2, "attempts": 1 }   one event, one delivery, one call

# The receiver saw it exactly once:
curl -s localhost:8082/control/received | jq '.totalReceived'

# And the same id with a *different* payload is refused rather than silently absorbed:
curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8080/api/v1/events \
  -H 'Content-Type: application/json' \
  -d '{"eventId":"evt_ac4","type":"incident.created","occurredAt":"2026-09-15T10:00:00Z","payload":{"severity":"low"}}'
# → 409
```

### AC5 — inspectable history

```bash
curl -s localhost:8080/api/v1/events/evt_ac3 | jq
```

One call, across both services: the event as submitted, how the handoff went, and every attempt
in order with its status, duration, response snippet, what it scheduled next, and which instance
made it.

---

## Delivery semantics

### What gets retried

Retryability is a whitelist, not "everything that is not a 2xx". We retry only what a later
attempt could plausibly succeed at — a `422` means the body is wrong and will be equally wrong in
ten minutes.

| | |
|---|---|
| **Success** | `2xx` |
| **Retryable** | `408`, `425`, `429`, `500`, `502`, `503`, `504`<br>connect timeout, read timeout, connection refused, DNS failure, connection reset |
| **Permanent** | every other `4xx`, plus `1xx`, `3xx` (redirects are not followed), `501`, `505`, TLS handshake failure, malformed URL |

Redirects are deliberately not followed: a `3xx` from a webhook endpoint is a misconfiguration,
and following one would post customer data somewhere the configuration never named.

### Backoff

`delay(n) = min(base × multiplier^(n-1), max)`, with ±20% jitter, written into the delivery row
as an absolute time. **Five attempts total.**

| Profile | Schedule | Used by |
|---|---|---|
| `default` | 5s → 25s → 125s → 600s (capped) | production |
| `demo` | 1s → 2s → 4s → 8s | `make up`, so a failure and its retries fit in a short video |

A `Retry-After` header on a `429` or `503` overrides the computed delay and is **not** jittered —
the receiver named a time. It is clamped to `[1s, max]`, because a header is not permission to
schedule an attempt an hour out.

### The guarantee: at-least-once

**We do not promise exactly-once, and cannot.** A receiver can commit a write and have the
response lost on the way back; from here that is indistinguishable from the write never happening,
and the only safe move is to try again.

So instead we promise that **a repeat is always recognisable**. Every attempt of a given delivery
carries the same key:

```
X-Idempotency-Key: 8f2c1b4e-…        the delivery id — identical on every attempt
X-Webhook-Event-Id: evt_123          the id you submitted
X-Webhook-Delivery-Id: 8f2c1b4e-…    same as the idempotency key
X-Webhook-Attempt: 3                 which attempt this is
X-Webhook-Timestamp: 2026-09-15T10:00:00Z
X-Webhook-Event-Type: incident.created
```

**If you are writing a receiver:** record `X-Idempotency-Key` with the effect it produced, inside
the same transaction as that effect, and ignore a key you have already seen. Do not dedupe on
`X-Webhook-Attempt` — it changes between duplicates. Do not dedupe on the body — it is equivalent
JSON but not byte-identical, because it round-trips through a `jsonb` column.

What can still reach you twice is set out in
[SUBMISSION.md](SUBMISSION.md#what-could-still-cause-a-receiver-to-observe-a-duplicate-delivery).

---

## Tests

```bash
make test        # or: ./mvnw clean verify
```

**879 tests**, about 2 minutes. Needs a Docker socket — Testcontainers starts its own
PostgreSQL 16. No paid service, no network account, nothing to configure.

Never H2: `FOR UPDATE SKIP LOCKED`, `ON CONFLICT DO NOTHING` and partial indexes are the
behaviours under test, so an embedded database would make a green suite evidence about the wrong
database. If you cannot open a Docker socket, [docs/LOCAL_DEV.md](docs/LOCAL_DEV.md) has a
no-Docker, no-root fallback.

Nothing in the suite sleeps or polls. The clock is injected and mutable, the scheduler is ticked
by name, and the worker pool runs on the calling thread under the `test` profile — so
`scheduler.runOnce()` returns only once every delivery it claimed has finished, and a test that
needs to be ten minutes into a backoff simply says so.

---

## How it works, in one paragraph

Ingest writes the event and an outbox row **in one transaction**, so there is no moment where a
caller holds a `201` for an event nobody will deliver. A dispatcher drains the outbox into the
delivery service, which is idempotent on `event_id`. **The queue is a table**: `next_attempt_at`
plus `SELECT … FOR UPDATE SKIP LOCKED`, so any number of instances can poll the same index with
no coordinator, claiming only as much as their worker pool can start — the backlog stays in
PostgreSQL where a restart cannot lose it. An attempt is counted *before* the HTTP call, which is
what makes the five-attempt bound survive a crash; a worker that dies leaves an expired lease that
a reaper reclaims, at startup and every ten seconds.

The full picture, including the state machine and the reasoning behind each choice, is in
[ARCHITECTURE.md](ARCHITECTURE.md).

---

## Poking at it

```bash
curl localhost:8080/api/v1/events                                  # accepted events, newest first
curl 'localhost:8081/internal/v1/deliveries?status=FAILED_EXHAUSTED'   # the dead-letter view
curl localhost:8081/internal/v1/deliveries/{id}/attempts           # one delivery's history
curl localhost:8082/control/received                               # what the receiver actually got
curl -s localhost:8081/actuator/prometheus | grep webhook_         # the meters
docker compose -f deploy/docker/docker-compose.yml --profile tools up -d adminer   # browse the tables
```

Receiver modes, for driving failures: `ALWAYS_OK`, `ALWAYS_FAIL`, `FAIL_N_THEN_OK`, `TIMEOUT`,
each accepting `status`, `failCount`, `delayMs` and `retryAfterSeconds`.

Scaling, to watch two instances share one queue:

```bash
docker compose -f deploy/docker/docker-compose.yml \
               -f deploy/docker/docker-compose.scale.yml \
               up -d --scale delivery-service=3
# then look at worker_id in any attempt history — the work splits, and nothing is delivered twice
```

The overlay drops the delivery service's published port, because several replicas cannot share
one and a *range* would leave the browser unable to find it. Replicas still reach each other by
service name, so delivery is unaffected — but the demo UI's delivery panels stop working while
scaled. Scaling is something to observe in the data, not in the browser.
