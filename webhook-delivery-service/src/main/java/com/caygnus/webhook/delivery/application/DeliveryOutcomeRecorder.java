package com.caygnus.webhook.delivery.application;

import com.caygnus.webhook.delivery.domain.BackoffPolicy;
import com.caygnus.webhook.delivery.domain.Delivery;
import com.caygnus.webhook.delivery.domain.DeliveryAttempt;
import com.caygnus.webhook.delivery.domain.DeliveryOutcome;
import com.caygnus.webhook.delivery.infrastructure.persistence.DeliveryAttemptRepository;
import com.caygnus.webhook.delivery.infrastructure.observability.DeliveryMetrics;
import com.caygnus.webhook.delivery.infrastructure.persistence.DeliveryRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The outcome transaction: one attempt row written, one state transition applied, atomically.
 *
 * <p>Both halves have to land together. An attempt recorded without the transition would leave a
 * delivery in flight forever with a history that says otherwise; a transition without the attempt
 * would move the delivery on with no evidence of why. Either way the history stops being the
 * account of what happened, which is the one thing it is for.
 *
 * <p>Separate from {@link DeliveryExecutor} for the same proxying reason as
 * {@link DeliveryClaimService}: a transactional method called from inside its own bean is not
 * transactional at all.
 */
@Service
public class DeliveryOutcomeRecorder {

    private static final Logger log = LoggerFactory.getLogger(DeliveryOutcomeRecorder.class);

    private final DeliveryRepository deliveries;
    private final DeliveryAttemptRepository attempts;
    private final BackoffPolicy backoff;
    private final WorkerIdentity worker;
    private final DeliveryMetrics metrics;
    private final Clock clock;

    DeliveryOutcomeRecorder(
            DeliveryRepository deliveries,
            DeliveryAttemptRepository attempts,
            BackoffPolicy backoff,
            WorkerIdentity worker,
            DeliveryMetrics metrics,
            Clock clock) {
        this.deliveries = deliveries;
        this.attempts = attempts;
        this.backoff = backoff;
        this.worker = worker;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * Apply what happened.
     *
     * <p>The delivery is reloaded rather than carried over from the claim, so the write is made
     * against the current row and {@code @Version} can catch a reaper that got there first.
     *
     * @param startedAt   when the request went out
     * @param completedAt when it came back, or gave up
     */
    @Transactional
    public void record(ClaimedDelivery claimed, DeliveryOutcome outcome, Instant startedAt, Instant completedAt) {
        Delivery delivery = deliveries.findById(claimed.deliveryId()).orElseThrow(() ->
                new IllegalStateException("Delivery %s vanished mid-attempt".formatted(claimed.deliveryId())));

        Instant now = clock.instant();
        switch (outcome.outcome()) {
            case SUCCESS -> delivery.recordSuccess(now);
            case PERMANENT_FAILURE -> delivery.recordPermanentFailure(now);
            // The delivery decides whether a retry is actually left; computing a time for one it
            // will not take is cheap, and asking first would put the bound in two places.
            case RETRYABLE_FAILURE -> delivery.recordRetryableFailure(
                    now, backoff.nextAttemptAt(claimed.attemptNumber(), outcome.retryAfterSeconds()));
        }

        attempts.save(DeliveryAttempt.record(
                claimed.deliveryId(),
                claimed.attemptNumber(),
                startedAt,
                completedAt,
                outcome.outcome(),
                outcome.httpStatus(),
                outcome.errorClass(),
                outcome.errorMessage(),
                outcome.responseBodySnippet(),
                outcome.retryAfterSeconds(),
                // What this attempt actually scheduled, as decided above -- null once terminal.
                delivery.getNextAttemptAt(),
                worker.value()));
        deliveries.save(delivery);

        metrics.attemptCompleted(outcome.outcome(), Duration.between(startedAt, completedAt));
        if (delivery.isTerminal()) {
            metrics.deliveryFinished(delivery.getStatus(), delivery.getTerminalReason());
        }

        // One line per attempt at INFO; the MDC supplies deliveryId, eventId, attemptNumber and
        // workerId, so the message carries only what it adds.
        log.info("Attempt {}/{} -> {} status={} state={} nextAttemptAt={}",
                claimed.attemptNumber(), claimed.maxAttempts(),
                outcome.outcome(), outcome.httpStatus(), delivery.getStatus(), delivery.getNextAttemptAt());
        if (delivery.isTerminal() && delivery.getTerminalReason() != null) {
            log.warn("Delivery finished without being delivered: {} after {} attempts",
                    delivery.getTerminalReason(), delivery.getAttemptCount());
        }
    }
}
