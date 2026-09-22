package com.caygnus.webhook.delivery.application;

import com.caygnus.webhook.common.model.DeliveryStatus;
import com.caygnus.webhook.delivery.config.DeliveryProperties;
import com.caygnus.webhook.delivery.config.SchedulerProperties;
import com.caygnus.webhook.delivery.domain.Delivery;
import com.caygnus.webhook.delivery.infrastructure.persistence.DeliveryRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The claim transaction, and nothing else.
 *
 * <p>It is a separate bean from {@link DeliveryScheduler} for a reason that is easy to get wrong:
 * {@code @Transactional} only applies through the Spring proxy, so a scheduler calling its own
 * transactional method would silently run it with no transaction at all -- and the row locks that
 * make {@code SKIP LOCKED} safe would be released the instant the query returned. Keeping the
 * boundary on a collaborator makes that impossible rather than merely unlikely.
 *
 * <p>Nothing in here does I/O. The transaction covers a lock, a load and an update, and then it is
 * over; the HTTP call happens afterwards, on someone else's time.
 */
@Service
public class DeliveryClaimService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryClaimService.class);

    private final DeliveryRepository deliveries;
    private final WorkerIdentity worker;
    private final Duration leaseDuration;
    private final Clock clock;

    DeliveryClaimService(
            DeliveryRepository deliveries,
            WorkerIdentity worker,
            DeliveryProperties deliveryProperties,
            SchedulerProperties schedulerProperties,
            Clock clock) {
        this.deliveries = deliveries;
        this.worker = worker;
        // Long enough that an attempt which is merely slow cannot be reaped out from under itself:
        // the response timeout must have already fired before the lease is even eligible.
        this.leaseDuration = deliveryProperties.responseTimeout().plus(schedulerProperties.leaseSlack());
        this.clock = clock;
    }

    /**
     * Take ownership of up to {@code limit} due deliveries.
     *
     * <p>{@code SKIP LOCKED} means several instances can run this at the same moment against the
     * same index and come away with disjoint sets, without a coordinator and without any of them
     * waiting. Each claim spends an attempt from the budget before the call goes out, so a process
     * that dies mid-attempt has still used it -- that is what makes the bound survive a crash.
     */
    @Transactional
    public List<ClaimedDelivery> claimDue(int limit) {
        Instant now = clock.instant();
        List<UUID> lockedIds = deliveries.lockDueDeliveryIds(now, limit);
        if (lockedIds.isEmpty()) {
            return List.of();
        }

        List<Delivery> claimed = deliveries.findAllById(lockedIds);
        claimed.forEach(delivery -> delivery.claim(worker.value(), now, leaseDuration));
        deliveries.saveAll(claimed);

        log.debug("Claimed {} deliveries as {}", claimed.size(), worker);
        return claimed.stream().map(ClaimedDelivery::of).toList();
    }

    /**
     * Take back deliveries whose worker stopped reporting.
     *
     * <p>An expired lease means one thing: a process held this delivery, said it was attempting
     * it, and never came back to say how it went. Since the attempt was counted before the
     * request went out, the budget has already been spent and is <em>not</em> refunded -- the
     * call may well have reached the receiver, and pretending otherwise is how a bound gets
     * exceeded across a crash.
     *
     * <p>Which is also why a delivery whose budget ran out while in flight is finished here rather
     * than re-queued: re-queueing it would move it to a state the claim query can see but the
     * domain would refuse to claim, and it would sit there being looked at forever.
     *
     * @return how many were re-queued and how many were finished off
     */
    @Transactional
    public ReclaimResult reclaimExpiredLeases(int limit) {
        Instant now = clock.instant();
        List<UUID> expired = deliveries.lockExpiredLeaseIds(now, limit);
        if (expired.isEmpty()) {
            return ReclaimResult.NOTHING;
        }

        int requeued = 0;
        int exhausted = 0;
        List<Delivery> abandoned = deliveries.findAllById(expired);
        for (Delivery delivery : abandoned) {
            String lostOwner = delivery.getLeaseOwner();
            delivery.reapExpiredLease(now);
            if (delivery.getStatus() == DeliveryStatus.FAILED_EXHAUSTED) {
                exhausted++;
            } else {
                requeued++;
            }
            // Logged per delivery and at WARN on purpose: a steady trickle of these is the
            // clearest signal available that instances are dying mid-attempt.
            log.warn("Reclaimed deliveryId={} from lapsed lease owner={} after attempt {}/{} -> {}",
                    delivery.getId(), lostOwner, delivery.getAttemptCount(), delivery.getMaxAttempts(),
                    delivery.getStatus());
        }
        deliveries.saveAll(abandoned);
        return new ReclaimResult(requeued, exhausted);
    }

    /**
     * Give a claim back without having used it.
     *
     * <p>Only legitimate when the attempt provably never started -- the executor rejected the task
     * before running it. The attempt is refunded precisely because no request was made, which is
     * the opposite of what {@link Delivery#reapExpiredLease} assumes about a worker that vanished
     * mid-flight. Letting overload consume a delivery's budget would fail events that never got a
     * chance; the scheduler only ever claims what it has capacity for, so this stays a rare race
     * rather than a state the system can sit in.
     */
    @Transactional
    public void releaseUnstarted(UUID deliveryId) {
        deliveries.findById(deliveryId).ifPresent(delivery -> {
            delivery.releaseUnstartedClaim(clock.instant());
            deliveries.save(delivery);
            log.warn("Released an unstarted claim on deliveryId={}; the executor had no capacity", deliveryId);
        });
    }

    /**
     * @param requeued  put back in the queue with attempts still to spend
     * @param exhausted finished as {@code FAILED_EXHAUSTED}, having spent the last one in flight
     */
    public record ReclaimResult(int requeued, int exhausted) {

        static final ReclaimResult NOTHING = new ReclaimResult(0, 0);

        public int total() {
            return requeued + exhausted;
        }
    }
}
