package com.caygnus.webhook.ingest.application;

import com.caygnus.webhook.ingest.config.OutboxProperties;
import com.caygnus.webhook.ingest.domain.DispatchBackoff;
import com.caygnus.webhook.ingest.domain.OutboxDispatch;
import com.caygnus.webhook.ingest.infrastructure.client.DeliveryServiceClient;
import com.caygnus.webhook.ingest.infrastructure.client.DispatchOutcome;
import com.caygnus.webhook.ingest.infrastructure.persistence.OutboxDispatchRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drains the transactional outbox: takes each recorded intent to deliver an event and hands it to
 * the delivery service.
 *
 * <p>This is the half of the handoff that makes it lossless. Ingestion committed the event and
 * the intent together, so there is no state in which a caller holds a 201 for an event nobody
 * will deliver. All that remains is to keep trying until the far end acknowledges -- and because
 * the delivery service is idempotent on {@code event_id}, "keep trying" is safe even when we
 * cannot tell whether the previous attempt arrived.
 *
 * <p><b>The one deliberate exception to "no network I/O inside a database transaction"
 * (ARCHITECTURE.md §2 invariant 4, scoped in §6).</b> A tick claims its rows with
 * {@code FOR UPDATE SKIP LOCKED} and holds that transaction across the HTTP calls, so the locks
 * still exist when the outcomes are written. Everywhere else in this system the rule is absolute;
 * here it is broken on purpose, under these constraints:
 *
 * <ul>
 *   <li><b>Bounded.</b> At most {@code batchSize} rows (20) times the read timeout (5s) of held
 *       transaction in the pathological case, and that requires every call in the batch to time
 *       out rather than fail fast, which a refused connection does not.
 *   <li><b>Internal.</b> The call goes to a service we operate at a configured address, not to an
 *       arbitrary customer endpoint. The untrusted call -- the one to the receiver, which really
 *       can hang for as long as it likes -- is in the delivery service and is firmly outside any
 *       transaction.
 *   <li><b>Both timeouts set.</b> Connect and read, so the transaction cannot be held open by a
 *       peer that simply stops responding.
 *   <li><b>Not load-bearing.</b> The lock only stops two instances making the same call; it is
 *       not what makes the handoff correct. That is {@code UNIQUE(delivery.event_id)} at the far
 *       end, which is why a duplicate dispatch is harmless rather than merely unlikely.
 * </ul>
 *
 * <p>The alternative is claim-commit-call-commit, as everywhere else. It costs a second round trip
 * per row and needs its own lease-and-reaper to recover rows claimed by a process that then died
 * -- machinery this path does not otherwise need, because a row left {@code PENDING} is simply
 * picked up by the next tick. If the dispatcher ever became hot enough for the held transaction
 * to matter, that is the change to make, and it is recorded in {@code SUBMISSION.md} as such.
 */
@Service
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final OutboxDispatchRepository outbox;
    private final DeliveryServiceClient deliveryService;
    private final DispatchBackoff backoff;
    private final int batchSize;
    private final int maxAttempts;
    private final Clock clock;

    OutboxDispatcher(
            OutboxDispatchRepository outbox,
            DeliveryServiceClient deliveryService,
            OutboxProperties properties,
            Clock clock) {
        this.outbox = outbox;
        this.deliveryService = deliveryService;
        this.backoff = new DispatchBackoff(
                properties.baseDelay(), properties.multiplier(), properties.maxDelay(), clock);
        this.batchSize = properties.batchSize();
        this.maxAttempts = properties.maxAttempts();
        this.clock = clock;
    }

    /**
     * One pass over the due intents.
     *
     * @return how many were handed over successfully, which is 0 on a quiet tick
     */
    @Transactional
    public int runOnce() {
        List<Long> dueIds = outbox.lockPendingIds(clock.instant(), batchSize);
        if (dueIds.isEmpty()) {
            return 0;
        }

        int dispatched = 0;
        List<OutboxDispatch> due = outbox.findAllById(dueIds);
        for (OutboxDispatch intent : due) {
            if (dispatch(intent)) {
                dispatched++;
            }
        }
        outbox.saveAll(due);
        return dispatched;
    }

    private boolean dispatch(OutboxDispatch intent) {
        DispatchOutcome outcome = deliveryService.dispatch(intent.getEventId(), intent.getPayload());
        Instant now = clock.instant();

        if (outcome.accepted()) {
            intent.markDispatched(outcome.deliveryId(), now);
            log.info("Dispatched eventId={} -> deliveryId={}", intent.getEventId(), outcome.deliveryId());
            return true;
        }

        if (outcome.retryable()) {
            // attempts is incremented inside, so the backoff is computed from the count *after*
            // this failure -- the first failure waits one base delay, not none.
            intent.recordFailedAttempt(outcome.error(), backoff.nextDispatchAt(intent.getAttempts() + 1),
                    now, maxAttempts);
            if (intent.isPending()) {
                log.warn("Dispatch of eventId={} failed ({} of {}), retrying at {}: {}",
                        intent.getEventId(), intent.getAttempts(), maxAttempts,
                        intent.getNextDispatchAt(), outcome.error());
            } else {
                // Loud, because the event is now stuck: accepted, durable, and going nowhere
                // until someone looks at it.
                log.error("Giving up on eventId={} after {} attempts. The event is retained and "
                                + "the delivery service never acknowledged it. Last error: {}",
                        intent.getEventId(), intent.getAttempts(), outcome.error());
            }
            return false;
        }

        log.error("The delivery service rejected eventId={} outright; not retrying: {}",
                intent.getEventId(), outcome.error());
        intent.markRejected(outcome.error(), now);
        return false;
    }
}
