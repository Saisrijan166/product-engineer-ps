package com.caygnus.webhook.delivery.application;

import com.caygnus.webhook.common.model.ErrorClass;
import com.caygnus.webhook.delivery.domain.DeliveryOutcome;
import com.caygnus.webhook.delivery.infrastructure.health.EndpointHealthGate;
import com.caygnus.webhook.delivery.infrastructure.http.WebhookHttpClient;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * One attempt, start to finish: call the receiver, then record what happened.
 *
 * <p>The shape of this class is the design's central constraint made literal. The HTTP call sits
 * between two transactions and inside neither. A database connection held across a five-second
 * call to someone else's server is a connection the pool has lost for five seconds, and a
 * receiver that hangs would take the whole pool down with it. So: claim and commit, call, record
 * and commit.
 *
 * <p>Nothing here is allowed to throw. A worker thread that dies leaves a delivery in flight until
 * its lease expires, and while the reaper would eventually collect it, "eventually" is the wrong
 * answer to a problem that is already understood.
 */
@Component
public class DeliveryExecutor {

    private static final Logger log = LoggerFactory.getLogger(DeliveryExecutor.class);

    private final WebhookHttpClient httpClient;
    private final DeliveryOutcomeRecorder recorder;
    private final EndpointHealthGate healthGate;
    private final Clock clock;

    DeliveryExecutor(
            WebhookHttpClient httpClient,
            DeliveryOutcomeRecorder recorder,
            EndpointHealthGate healthGate,
            Clock clock) {
        this.httpClient = httpClient;
        this.recorder = recorder;
        this.healthGate = healthGate;
        this.clock = clock;
    }

    public void execute(ClaimedDelivery claimed) {
        Instant startedAt = clock.instant();

        DeliveryOutcome outcome = attempt(claimed);

        try {
            recorder.record(claimed, outcome, startedAt, clock.instant());
        } catch (RuntimeException e) {
            // The attempt happened; we simply failed to write it down. Leaving the delivery in
            // flight is the honest outcome -- the lease will expire and the reaper will requeue it,
            // which is exactly the at-least-once behaviour we document rather than a new failure
            // mode. Swallowing it here keeps one bad row from killing the worker thread.
            log.error("Could not record the outcome of attempt {} for deliveryId={}; "
                            + "leaving it in flight for the reaper",
                    claimed.attemptNumber(), claimed.deliveryId(), e);
        }
    }

    /**
     * Make the call, unless the endpoint is being given a rest.
     *
     * <p>A short-circuited attempt is still an attempt: it gets a row, it spends one from the
     * budget, and it is rescheduled on the ordinary backoff. That is the honest accounting -- the
     * delivery really did fail to go out, and hiding that would leave a history that does not
     * explain the gaps in itself. The consequence, worth being clear about, is that a delivery
     * whose whole remaining budget falls inside one open window can exhaust without a single
     * request leaving the process; the window is deliberately shorter than the later backoffs so
     * that stays a corner case rather than the normal path.
     *
     * <p>Only real calls are reported back to the gate. Feeding a refusal into it would have the
     * gate holding itself open on the strength of its own refusals.
     */
    private DeliveryOutcome attempt(ClaimedDelivery claimed) {
        if (!healthGate.allow(claimed.targetUrl())) {
            log.warn("Skipped attempt {} for deliveryId={}: {} is being given a rest",
                    claimed.attemptNumber(), claimed.deliveryId(), claimed.targetUrl());
            return DeliveryOutcome.fromTransportFailure(ErrorClass.CIRCUIT_OPEN,
                    "No request was made: recent attempts on this endpoint kept failing");
        }

        DeliveryOutcome outcome = httpClient.send(claimed);
        healthGate.recordOutcome(claimed.targetUrl(), outcome.outcome());
        return outcome;
    }
}
