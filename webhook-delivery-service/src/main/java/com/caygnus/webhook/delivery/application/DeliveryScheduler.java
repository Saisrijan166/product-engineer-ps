package com.caygnus.webhook.delivery.application;

import com.caygnus.webhook.delivery.config.DeliveryProperties;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * The queue's pump: claim what is due and hand it to a worker.
 *
 * <p>There is no in-memory queue. The scheduler asks for no more work than the executor can start
 * right now, so anything it does not claim simply stays in PostgreSQL and is still there on the
 * next tick -- or gets picked up by a different instance, which is the same thing as far as the
 * system is concerned. Backpressure is therefore automatic and survives a restart, because the
 * backlog was never anywhere else.
 *
 * <p>{@link #runOnce()} is public and does the entire job. The {@code @Scheduled} trigger is a
 * separate bean that does nothing but call it, which means tests drive the queue by asking rather
 * than by waiting -- and the thing they drive is the same code production runs.
 */
@Component
public class DeliveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(DeliveryScheduler.class);

    private final DeliveryClaimService claims;
    private final DeliveryExecutor executor;
    private final TaskExecutor taskExecutor;
    private final int maxConcurrentAttempts;

    DeliveryScheduler(
            DeliveryClaimService claims,
            DeliveryExecutor executor,
            @Qualifier("deliveryTaskExecutor") TaskExecutor deliveryTaskExecutor,
            DeliveryProperties properties) {
        this.claims = claims;
        this.executor = executor;
        this.taskExecutor = deliveryTaskExecutor;
        this.maxConcurrentAttempts = properties.maxConcurrentAttempts();
    }

    /**
     * One pass: work out the free capacity, claim that much, start it.
     *
     * @return how many deliveries were claimed, which is 0 on a quiet tick
     */
    public int runOnce() {
        int capacity = freeCapacity();
        if (capacity <= 0) {
            log.debug("All {} workers busy; leaving the backlog in PostgreSQL", maxConcurrentAttempts);
            return 0;
        }

        List<ClaimedDelivery> claimed = claims.claimDue(capacity);
        claimed.forEach(this::submit);
        return claimed.size();
    }

    /**
     * Free slots, or the batch size when the executor cannot say.
     *
     * <p>{@code getActiveCount()} is an estimate, so this can be off by one under load and a
     * submission can still be refused -- {@link #submit} handles that rather than pretending it
     * cannot happen. Under the test profile the executor is synchronous and reports nothing, so
     * the fallback is the configured batch size.
     */
    private int freeCapacity() {
        if (taskExecutor instanceof ThreadPoolTaskExecutor pool) {
            return pool.getMaxPoolSize() - pool.getActiveCount();
        }
        return maxConcurrentAttempts;
    }

    private void submit(ClaimedDelivery claimed) {
        try {
            taskExecutor.execute(new DeliveryTask(claimed, () -> executor.execute(claimed)));
        } catch (TaskRejectedException rejected) {
            // Claimed but never started, so the attempt is given back: the delivery becomes due
            // again immediately instead of waiting out a lease it is not really holding.
            log.warn("No capacity to start deliveryId={}; returning the claim", claimed.deliveryId(), rejected);
            claims.releaseUnstarted(claimed.deliveryId());
        }
    }
}
