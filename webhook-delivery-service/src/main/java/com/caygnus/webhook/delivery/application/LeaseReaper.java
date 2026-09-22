package com.caygnus.webhook.delivery.application;

import com.caygnus.webhook.delivery.application.DeliveryClaimService.ReclaimResult;
import com.caygnus.webhook.delivery.config.ReaperProperties;
import com.caygnus.webhook.delivery.infrastructure.observability.DeliveryMetrics;
import com.caygnus.webhook.delivery.infrastructure.persistence.DeliveryRepository;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Crash recovery: the thing that makes "kill any process at any instant" survivable.
 *
 * <p>A worker that dies mid-attempt leaves its delivery marked in flight, holding a lease nobody
 * is honouring. Nothing else in the system will ever look at it again -- the claim query selects
 * only {@code PENDING} and {@code RETRY_SCHEDULED}, so an abandoned delivery is invisible to it
 * precisely because that filter is what keeps finished work from being retried. This is the one
 * component whose job is to notice.
 *
 * <p>It runs on a timer <em>and</em> once at startup, and the second is not redundant. The common
 * case is an instance dying and coming back: whatever it was holding is stranded, and the
 * deliveries it abandoned are the ones most likely to be waiting on it. Sweeping before serving
 * turns a restart into a pause rather than a gap.
 *
 * <p>Like {@link DeliveryScheduler}, this is a plain bean with public methods; the timer and the
 * startup hook live on a separate conditional trigger, so tests drive the same code by name.
 */
@Component
public class LeaseReaper {

    /**
     * A ceiling on the startup sweep, so a pathological backlog delays serving by a bounded amount
     * rather than indefinitely. Anything left over is picked up by the ordinary timer.
     */
    private static final int MAX_STARTUP_PASSES = 100;

    private static final Logger log = LoggerFactory.getLogger(LeaseReaper.class);

    private final DeliveryClaimService claims;
    private final DeliveryRepository deliveries;
    private final DeliveryMetrics metrics;
    private final int batchSize;
    private final Clock clock;

    LeaseReaper(
            DeliveryClaimService claims,
            DeliveryRepository deliveries,
            ReaperProperties properties,
            DeliveryMetrics metrics,
            Clock clock) {
        this.claims = claims;
        this.deliveries = deliveries;
        this.metrics = metrics;
        this.batchSize = properties.batchSize();
        this.clock = clock;
    }

    /**
     * One sweep, bounded by the configured batch size.
     *
     * @return how many deliveries were reclaimed, which is 0 on a healthy tick
     */
    public int runOnce() {
        ReclaimResult result = claims.reclaimExpiredLeases(batchSize);
        if (result.total() > 0) {
            metrics.leaseReclaimed(result.total());
            log.warn("Reclaimed {} abandoned deliveries: {} requeued, {} out of attempts",
                    result.total(), result.requeued(), result.exhausted());
        }
        return result.total();
    }

    /**
     * Sweep until nothing is left, then report what the process woke up to.
     *
     * <p>The summary is the first thing worth reading in the log of a restarted instance: how much
     * was stranded, and how much work is waiting. Both being zero is the boring case, and boring
     * is what it should say.
     */
    public void recoverOnStartup() {
        int reclaimed = 0;
        int passes = 0;
        while (passes < MAX_STARTUP_PASSES) {
            int swept = runOnce();
            reclaimed += swept;
            passes++;
            // A short batch means the backlog is drained; only a full one implies there is more.
            if (swept < batchSize) {
                break;
            }
        }
        if (passes == MAX_STARTUP_PASSES) {
            log.warn("Startup recovery stopped after {} passes with work still outstanding; "
                    + "the scheduled reaper will continue", MAX_STARTUP_PASSES);
        }

        long due = deliveries.countDue(clock.instant());
        log.info("Startup recovery complete: reclaimed={} dueDeliveries={}", reclaimed, due);
    }
}
