# Running the build locally

Two paths. **Docker is the one reviewers use and the one the submission documents.** The second
exists because this development machine cannot open the Docker socket.

---

## Path A — Docker (the normal path)

What the test suite needs is a Docker socket the current account can open; Testcontainers starts
and disposes of `postgres:16-alpine` by itself.

### One-time setup

```bash
sudo usermod -aG docker $USER
```

Then **log out and back in** — group membership is read at login, so a new terminal in the same
session is not enough. To test it without logging out:

```bash
newgrp docker
```

Confirm:

```bash
docker info >/dev/null && echo OK
```

> **Worth knowing before you run it.** Membership of the `docker` group is equivalent to root on
> this machine: anyone in it can start a container that bind-mounts the host filesystem. That is
> the ordinary trade-off on a development box, but it is a real privilege grant rather than a
> formality. Path B avoids it entirely.

### Build and test

```bash
./mvnw clean verify
```

First run pulls the PostgreSQL image (~80 MB) and takes a couple of minutes; later runs reuse it.

---

## Path B — a local PostgreSQL, no Docker and no root

The `postgresql-16` package ships the server binaries. A cluster can be initialised under `/tmp`
and run as the current user, which needs neither the `docker` group nor `sudo`. It is real
PostgreSQL 16 — the same major version as the container — so the suite tests exactly what it
tests under Path A.

```bash
./scripts/local-postgres.sh start
```

That prints the three variables to export:

```bash
export TEST_DB_URL=jdbc:postgresql://127.0.0.1:55432/webhook_delivery
export TEST_DB_USER=webhook
export TEST_DB_PASSWORD=webhook

./mvnw clean verify
```

With `TEST_DB_URL` set, `TestDatabase` binds to that server and never loads Testcontainers.
Unset it and the build returns to Path A with no other change.

```bash
./scripts/local-postgres.sh stop      # shut it down
./scripts/local-postgres.sh status    # is it up?
```

The cluster lives in `/tmp/webhook-retry-engine-pg` and is disposable — delete it and the next
`start` rebuilds it. `fsync` is off for the same reason.

**The one rule:** `TEST_DB_URL` must point at PostgreSQL 16. `FOR UPDATE SKIP LOCKED`,
`ON CONFLICT DO NOTHING` and partial indexes are the behaviours under test, so pointing it at
anything else — H2 above all — would make a green suite evidence about the wrong database.

---

## Running a subset

```bash
# Domain only: no database at all, finishes in about two seconds
./mvnw -pl webhook-delivery-service test -Dtest='*Test' -Dsurefire.failIfNoSpecifiedTests=false

# One class (-am builds webhook-common first, which it needs)
./mvnw -pl webhook-delivery-service -am test -Dtest=DeliveryApiTest
```

## Inspecting the test database

```bash
PGPASSWORD=webhook psql -h 127.0.0.1 -p 55432 -U webhook -d webhook_delivery

\dt                                  -- tables
\d delivery                          -- columns, constraints, indexes
SELECT status, count(*) FROM delivery GROUP BY status;
```

## Current state

All fifteen steps of `ARCHITECTURE.md` §18 are complete: **879 tests green.** All five acceptance criteria pass; crash recovery, zero-loss handoff, graceful degradation and endpoint gating are all proven.

| Suite | Tests | Needs a database |
| --- | ---: | :---: |
| `ResponseClassifierTest` | 548 | no |
| `DeliveryStateMachineTest` | 84 | no |
| `DeliveryTest` | 21 | no |
| `BackoffPolicyTest` | 19 | no |
| `EndpointHealthGateTest` | 17 | no |
| `DeliveryApiTest` | 22 | yes |
| `PermanentFailureTest` | 15 | yes |
| `SuccessfulDeliveryTest` (AC1) | 11 | yes |
| `CrashRecoveryTest` | 11 | yes |
| `CircuitOpenDeliveryTest` | 7 | yes |
| `AttemptUniquenessTest` | 10 | yes |
| `RetryThenSuccessTest` (AC2) | 8 | yes |
| `AttemptExhaustionTest` (AC3) | 7 | yes |
| `TransportFailureRetryTest` | 7 | yes |
| `SkipLockedClaimTest` / `EventUniquenessTest` | 6 each | yes |
| `OptimisticLockingTest` | 5 | yes |
| `ClaimIndexTest` | 4 | yes |
| `DeliveryApplicationTest` (schema + production wiring) | 2 | yes |
| `OutboxRecoveryTest` | 14 | yes |
| `EventHistoryApiTest` (AC5) | 8 | yes |
| `DeliveryServiceDownReadTest` | 6 | yes |
| `IdempotentIngestTest` (AC4) | 8 | yes |
| `PayloadHasherTest` | 8 | no |
| `PayloadMismatchTest` | 7 | yes |
| `ConcurrentIngestTest` (AC4) | 4 | yes |
| `IngestApplicationTest` (schema + dispatcher wiring) | 2 | yes |
| receiver smoke | 12 | no |

The two services own separate databases. `TEST_DB_URL` names the delivery one; the ingest module
swaps in `webhook_ingest` automatically, because each service runs its own Flyway migrations
against its own `flyway_schema_history` and sharing one database would have them fighting over it.
`TEST_INGEST_DB_URL` overrides that if you need it to.

### A note on the acceptance tests

`SuccessfulDeliveryTest` and everything that follows it extend `AbstractAcceptanceTest`, which
starts the **real** `webhook-test-receiver` in the same JVM on a random port. Nothing there sleeps
or polls: under the `test` profile the scheduler's timer is off, the worker pool is replaced by a
synchronous executor, and the clock is a `MutableTestClock`. So `scheduler.runOnce()` returns only
once every delivery it claimed has finished, and a test that needs to be ten minutes into a backoff
says `CLOCK.advance(...)` and asserts immediately.
