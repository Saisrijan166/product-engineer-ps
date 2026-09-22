#!/usr/bin/env bash
#
# Walks through all five acceptance criteria against a running stack.
#
#     make up && make demo
#
# Assumes the `demo` profile, whose retry schedule is 1s, 2s, 4s, 8s -- the whole of AC3 takes
# about fifteen seconds rather than the twelve minutes the production schedule would.
set -euo pipefail

# Event ids are scoped to this run, so `make demo` twice in a row reads cleanly instead of
# reporting the second run's events as resubmissions of the first.
RUN=${RUN:-$(date +%H%M%S)}

# Ports follow deploy/docker/.env when it exists, so a host that has to move one -- an 8081
# already in use, say -- does not need this script edited too.
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
if [[ -f "$HERE/deploy/docker/.env" ]]; then set -a; . "$HERE/deploy/docker/.env"; set +a; fi

INGEST=${INGEST:-http://localhost:${INGEST_PORT:-8080}}
DELIVERY=${DELIVERY:-http://localhost:${DELIVERY_PORT:-8081}}
RECEIVER=${RECEIVER:-http://localhost:${RECEIVER_PORT:-8082}}

bold() { printf '\n\033[1m%s\033[0m\n' "$*"; }
note() { printf '  %s\n' "$*"; }

post_event() {
    curl -sS -o /dev/null -w '%{http_code}' -X POST "$INGEST/api/v1/events" \
        -H 'Content-Type: application/json' \
        -d "{\"eventId\":\"$1\",\"type\":\"incident.created\",
             \"occurredAt\":\"2026-09-15T10:00:00Z\",
             \"payload\":{\"incidentId\":\"inc_456\",\"severity\":\"$2\"}}"
}

event() { curl -sS "$INGEST/api/v1/events/$1"; }

# Poll until the event reaches a state, so the script does not depend on how fast the machine is.
await_status() {
    local id=$1 want=$2 waited=0
    while [[ $waited -lt 60 ]]; do
        if [[ "$(event "$id" | python3 -c 'import json,sys; d=json.load(sys.stdin).get("delivery") or {}; print(d.get("status",""))' 2>/dev/null)" == "$want" ]]; then
            return 0
        fi
        sleep 1; waited=$((waited + 1))
    done
    echo "timed out waiting for $id to reach $want" >&2
    return 1
}

summarise() {
    event "$1" | python3 -c '
import json, sys
e = json.load(sys.stdin)
d = e.get("delivery") or {}
submissions = e["duplicateSubmissionCount"] + 1
reason = "  reason " + d["terminalReason"] if d.get("terminalReason") else ""
print("  event            {}  (submitted {}x)".format(e["eventId"], submissions))
print("  dispatch         {}".format(e["dispatch"]["status"]))
print("  delivery         {}  attempts {}/{}{}".format(
    d.get("status", "-"), d.get("attemptCount", 0), d.get("maxAttempts", 0), reason))
for a in d.get("attempts") or []:
    detail = "HTTP {}".format(a["httpStatus"]) if a.get("httpStatus") else (a.get("errorClass") or "")
    print("    attempt {}  {:<18} {:<22} worker {}".format(
        a["attemptNumber"], a["outcome"], detail, a["workerId"]))
'
}

curl -sS -o /dev/null -X POST "$RECEIVER/control/reset"

bold "AC1  successful delivery"
note "receiver answers 200; submit one event"
post_event evt_ac1_$RUN high > /dev/null
await_status evt_ac1_$RUN SUCCEEDED
summarise evt_ac1_$RUN

bold "AC2  temporary failure, then retry"
note "receiver fails twice with 503, then recovers"
curl -sS -o /dev/null -X POST "$RECEIVER/control/mode" -H 'Content-Type: application/json' \
    -d '{"mode":"FAIL_N_THEN_OK","failCount":2,"status":503}'
post_event evt_ac2_$RUN high > /dev/null
await_status evt_ac2_$RUN SUCCEEDED
summarise evt_ac2_$RUN

bold "AC3  bounded failure"
note "receiver never recovers; the engine stops after 5 attempts"
curl -sS -o /dev/null -X POST "$RECEIVER/control/mode" -H 'Content-Type: application/json' \
    -d '{"mode":"ALWAYS_FAIL","status":500}'
post_event evt_ac3_$RUN high > /dev/null
await_status evt_ac3_$RUN FAILED_EXHAUSTED
summarise evt_ac3_$RUN
note "and it appears in the dead-letter view:"
curl -sS "$DELIVERY/internal/v1/deliveries?status=FAILED_EXHAUSTED" \
    | python3 -c 'import json,sys; print("    {} delivery(ies) out of attempts".format(json.load(sys.stdin)["count"]))'

bold "AC4  idempotent ingestion"
curl -sS -o /dev/null -X POST "$RECEIVER/control/mode" -H 'Content-Type: application/json' -d '{"mode":"ALWAYS_OK"}'
note "the same eventId submitted three times:"
for _ in 1 2 3; do note "    HTTP $(post_event evt_ac4_$RUN high)"; done
note "201 once, 200 thereafter -- one event, one delivery, no second job"
await_status evt_ac4_$RUN SUCCEEDED
summarise evt_ac4_$RUN
note "same id with a *different* payload is refused:"
note "    HTTP $(post_event evt_ac4_$RUN low)   (409: a stable id must mean a stable event)"

bold "AC5  inspectable history"
note "everything above came from one call per event:"
note "    GET $INGEST/api/v1/events/evt_ac3_$RUN"
event evt_ac3_$RUN | python3 -m json.tool | head -40

bold "done"
note "receiver's own view:  curl $RECEIVER/control/received"
note "metrics:              curl $DELIVERY/actuator/prometheus | grep webhook_"
