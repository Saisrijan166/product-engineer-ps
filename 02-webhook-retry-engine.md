# Problem 2: Webhook Retry Engine

## Context

An incident platform sends events to external systems. Those systems may be unavailable, slow, or return errors. The platform needs clear delivery history and predictable retry behavior without creating multiple logical jobs for the same submitted event.

## Your objective

Build a small backend service that accepts an event and delivers it to one configured webhook endpoint.

The purpose is to demonstrate backend reliability, delivery semantics, retries, idempotent ingestion, and observability. A distributed production system is not expected.

## Minimum event contract

Your service should accept an equivalent of:

```json
{
  "eventId": "evt_123",
  "type": "incident.created",
  "occurredAt": "2026-09-15T10:00:00Z",
  "payload": {
    "incidentId": "inc_456",
    "severity": "high"
  }
}
```

Field names may differ, but the event must have a caller-supplied stable identifier.

## Required behavior

Your service must support:

1. Accepting and retaining an event before or as part of scheduling delivery
2. Delivering the event to one configurable HTTP endpoint
3. Recording each attempt with its time, attempt number, and outcome
4. Treating successful HTTP responses according to a documented policy
5. Retrying documented temporary failures with a bounded policy
6. Exposing delivery state and attempt history through an API, CLI, logs, or minimal interface
7. Treating repeated submissions with the same event identifier idempotently

Retry delays may be shortened or controlled during tests and demonstrations.

## Acceptance scenarios

### AC1: Successful delivery

**Given** a reachable webhook receiver returns a successful response  
**When** a valid event is submitted  
**Then** the event is delivered, marked successful, and has a recorded attempt

### AC2: Temporary failure and retry

**Given** the receiver temporarily fails according to the documented retry policy  
**When** a valid event is submitted  
**Then** the service records the failed attempt, retries it, and records the eventual outcome

### AC3: Bounded failure

**Given** the receiver continues to fail  
**When** the configured attempt limit is reached  
**Then** delivery stops, the final failed state is visible, and attempts do not continue forever

### AC4: Idempotent ingestion

**Given** an event with a particular `eventId` was already accepted  
**When** the same event is submitted again with that identifier  
**Then** the service returns or references the existing logical event and does not schedule a second independent delivery job

### AC5: Inspectable history

**Given** one or more delivery attempts occurred  
**When** the reviewer inspects the event  
**Then** the current state and ordered attempt history are available

Webhook delivery is commonly at least once. We do not require impossible exactly-once delivery across an external HTTP boundary. Document the semantics your implementation provides and how receivers should handle duplicate delivery attempts.

## Required tests

Include focused automated tests for:

- Successful delivery
- A temporary failure followed by a retry
- Attempt exhaustion or terminal failure
- Repeated ingestion of the same event identifier

Tests should use a local receiver, fake transport, or equivalent and must not rely on a paid service.

## Demo checklist

In the demo video, show:

1. The service and test receiver running
2. A successful event delivery
3. A receiver failure followed by retry behavior
4. The recorded attempt history
5. A repeated event submission handled idempotently

## Decisions you must document

- Which HTTP responses and errors are retryable
- The retry limit and delay or backoff policy
- The delivery guarantee you provide
- How concurrent duplicate submissions are handled
- What information is retained from an attempt

## Out of scope

- Authentication and multi-tenancy
- A management dashboard
- Multiple subscriber endpoints
- A distributed queue or multi-region deployment
- High-volume load testing
- Billing, quotas, or rate limits
- Production secret management

Request signing, manual replay, and endpoint health controls are optional. Keep them secondary to the required delivery behavior.

## What reviewers will pay attention to

- Explicit states and valid state transitions
- Persistence boundaries and behavior after process failure
- Idempotency under sequential and concurrent requests
- Clear retry classification rather than retrying every error
- Useful logs or delivery history
- Separation between event ingestion, scheduling, delivery, and storage
- Tests that do not depend on arbitrary sleep timing

## Questions to address in `SUBMISSION.md`

- What could still cause a receiver to observe a duplicate delivery?
- How would you operate this with many workers?
- How would you prevent one failing endpoint from consuming all capacity?
- What metrics and alerts would you add in production?