#!/usr/bin/env bash
#
# Asserts the five acceptance criteria from 02-webhook-retry-engine.md against a running stack,
# and exits non-zero if any of them regress.
#
#     make up && make verify
#
# The companion to scripts/demo.sh: that one narrates the same journey for the demo video and is
# meant to be read on screen, this one checks it and is meant to be trusted in CI.
#
# Assumes the `demo` profile (1s -> 2s -> 4s -> 8s backoff), so the whole run takes about a
# minute. Ports come from deploy/docker/.env if present, matching docker-compose.yml.
set -uo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
[[ -f "$HERE/deploy/docker/.env" ]] && set -a && . "$HERE/deploy/docker/.env" && set +a

INGEST=http://127.0.0.1:${INGEST_PORT:-8080}
DELIVERY=http://127.0.0.1:${DELIVERY_PORT:-8081}
RECEIVER=http://127.0.0.1:${RECEIVER_PORT:-8082}

pass=0; fail=0
bold() { printf '\n\033[1m%s\033[0m\n' "$*"; }
ok()   { printf '  \033[32m PASS\033[0m %s\n' "$*"; pass=$((pass+1)); }
bad()  { printf '  \033[31m FAIL\033[0m %s\n' "$*"; fail=$((fail+1)); }
check() { [[ "$2" == "$3" ]] && ok "$1 ($2)" || bad "$1 — expected $3, got $2"; }

post_event() {
    curl -s -o "${WH_BODY:-/tmp/wh-body}" -w '%{http_code}' -X POST "$INGEST/api/v1/events" \
        -H 'Content-Type: application/json' \
        -d "{\"eventId\":\"$1\",\"type\":\"incident.created\",
             \"occurredAt\":\"2026-09-15T10:00:00Z\",
             \"payload\":{\"incidentId\":\"inc_456\",\"severity\":\"${2:-high}\"}}"
    echo
}
event()   { curl -s "$INGEST/api/v1/events/$1"; }
status_of() { event "$1" | jq -r '.delivery.status // "PENDING"'; }
attempts_of() { event "$1" | jq -r '.delivery.attemptCount // 0'; }
mode() { curl -s -o /dev/null -X POST "$RECEIVER/control/mode" -H 'Content-Type: application/json' -d "$1"; }
reset_receiver() { curl -s -o /dev/null -X POST "$RECEIVER/control/reset"; }

# Polls for a terminal state rather than sleeping a guessed interval. The engine decides when
# it is done; this just stops looking once it is.
await_terminal() {
    for _ in $(seq 1 "${2:-60}"); do
        case "$(status_of "$1")" in
            SUCCEEDED|FAILED_PERMANENT|FAILED_EXHAUSTED) return 0 ;;
        esac
        sleep 1
    done
    return 1
}

command -v jq >/dev/null || { echo "jq is required: sudo apt install jq"; exit 2; }
for url in "$INGEST/actuator/health" "$DELIVERY/actuator/health" "$RECEIVER/actuator/health"; do
    curl -sf -m 5 "$url" >/dev/null || { echo "Not reachable: $url — is the stack up?"; exit 2; }
done
echo "Stack is up. ingest=$INGEST delivery=$DELIVERY receiver=$RECEIVER"

RUN=$(date +%s)

# ---------------------------------------------------------------- AC1
bold "AC1  A reachable receiver -> delivered, successful, one recorded attempt"
reset_receiver; mode '{"mode":"ALWAYS_OK"}'
EV="evt_ac1_$RUN"
check "POST /api/v1/events" "$(post_event "$EV")" 201
await_terminal "$EV" || bad "never reached a terminal state"
check "final status"   "$(status_of "$EV")"   SUCCEEDED
check "attempt count"  "$(attempts_of "$EV")" 1
check "attempt outcome" "$(event "$EV" | jq -r '.delivery.attempts[0].outcome')" SUCCESS
check "receiver saw it once" "$(curl -s "$RECEIVER/control/received" | jq '.requests | length')" 1
check "idempotency key == deliveryId" \
    "$(curl -s "$RECEIVER/control/received" | jq -r '.requests[0].headers["x-idempotency-key"]')" \
    "$(event "$EV" | jq -r '.delivery.deliveryId')"

# ---------------------------------------------------------------- AC2
bold "AC2  Temporary failure -> retried -> eventual outcome recorded"
reset_receiver; mode '{"mode":"FAIL_N_THEN_OK","failCount":2,"status":503}'
EV="evt_ac2_$RUN"
check "POST /api/v1/events" "$(post_event "$EV")" 201
await_terminal "$EV" || bad "never reached a terminal state"
check "final status"  "$(status_of "$EV")"   SUCCEEDED
check "attempt count" "$(attempts_of "$EV")" 3
check "outcome sequence" \
    "$(event "$EV" | jq -r '[.delivery.attempts[].outcome] | join(",")')" \
    "RETRYABLE_FAILURE,RETRYABLE_FAILURE,SUCCESS"
check "first two saw 503" \
    "$(event "$EV" | jq -r '[.delivery.attempts[0,1].httpStatus] | join(",")')" "503,503"
echo "  backoff actually applied:"
event "$EV" | jq -r '.delivery.attempts[] | "    attempt \(.attemptNumber)  \(.outcome)  next=\(.nextAttemptAt // "-")"'

# ---------------------------------------------------------------- AC3
bold "AC3  Persistent failure -> stops at the bound, final state visible"
reset_receiver; mode '{"mode":"ALWAYS_FAIL","status":500}'
EV="evt_ac3_$RUN"
check "POST /api/v1/events" "$(post_event "$EV")" 201
await_terminal "$EV" 90 || bad "never reached a terminal state"
check "final status"    "$(status_of "$EV")"   FAILED_EXHAUSTED
check "stopped at 5"    "$(attempts_of "$EV")" 5
check "terminal reason" "$(event "$EV" | jq -r '.delivery.terminalReason')" ATTEMPTS_EXHAUSTED
check "in the dead-letter view" \
    "$(curl -s "$DELIVERY/internal/v1/deliveries?status=FAILED_EXHAUSTED" \
        | jq --arg e "$EV" '[.deliveries[] | select(.eventId==$e)] | length')" 1
before=$(curl -s "$RECEIVER/control/received" | jq '.requests | length')
sleep 12
check "no further attempts after termination" \
    "$(curl -s "$RECEIVER/control/received" | jq '.requests | length')" "$before"

# ---------------------------------------------------------------- AC4
bold "AC4  The same eventId twice -> one logical event, one delivery job"
reset_receiver; mode '{"mode":"ALWAYS_OK"}'
EV="evt_ac4_$RUN"
check "first POST"  "$(post_event "$EV")" 201
await_terminal "$EV" || bad "never reached a terminal state"
FIRST_ID=$(event "$EV" | jq -r '.delivery.deliveryId')
check "second POST" "$(post_event "$EV")" 200
# The duplicate response references the delivery that already exists rather than making one.
check "duplicate references the same delivery" "$(jq -r '.deliveryId' /tmp/wh-body)" "$FIRST_ID"
check "duplicate counted"   "$(event "$EV" | jq -r '.duplicateSubmissionCount')" 1
check "still one attempt"   "$(attempts_of "$EV")" 1
check "receiver called once" "$(curl -s "$RECEIVER/control/received" | jq '.requests | length')" 1

echo "  20 concurrent submissions of one id:"
EV="evt_ac4_race_$RUN"
RACE=$(mktemp -d); trap 'rm -rf "$RACE"' EXIT
for i in $(seq 1 20); do
    (WH_BODY="$RACE/body.$i" post_event "$EV" > "$RACE/code.$i") &
done; wait
codes=$(cat "$RACE"/code.* | tr -d '\r')
check "twenty responses"  "$(grep -c . <<<"$codes")" 20
check "exactly one 201"   "$(grep -c '^201$' <<<"$codes")" 1
check "nineteen 200s"     "$(grep -c '^200$' <<<"$codes")" 19
check "one delivery for the raced id" \
    "$(curl -s "$DELIVERY/internal/v1/deliveries?eventId=$EV" | jq '.deliveries | length')" 1

# same id, a different body
check "changed payload rejected" "$(post_event "$EV" low)" 409

# ---------------------------------------------------------------- AC5
bold "AC5  State and ordered attempt history are inspectable"
EV="evt_ac2_$RUN"
check "attempts are ordered" \
    "$(event "$EV" | jq -r '[.delivery.attempts[].attemptNumber] | join(",")')" "1,2,3"
check "each attempt records a duration" \
    "$(event "$EV" | jq '[.delivery.attempts[] | select(.durationMs != null)] | length')" 3
check "each attempt records a worker" \
    "$(event "$EV" | jq '[.delivery.attempts[] | select(.workerId != null)] | length')" 3
echo "  composed read:"
event "$EV" | jq '{eventId, status: .delivery.status, attempts: [.delivery.attempts[]
    | {attemptNumber, outcome, httpStatus, durationMs}]}' | sed 's/^/    /'

# ---------------------------------------------------------------- summary
bold "$pass passed, $fail failed"
exit $(( fail > 0 ))
