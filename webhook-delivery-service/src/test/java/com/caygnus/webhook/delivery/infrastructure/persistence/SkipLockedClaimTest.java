package com.caygnus.webhook.delivery.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.caygnus.webhook.common.model.DeliveryStatus;
import com.caygnus.webhook.delivery.domain.Delivery;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The claim, which is the whole of the concurrency control.
 *
 * <p>Two instances poll the same index on the same column at the same time, with no coordinator
 * between them. {@code SKIP LOCKED} is what makes that safe: a row another transaction holds is not
 * waited for, it is passed over. Everything else in the design -- running several workers, scaling
 * the delivery service, the absence of any distributed lock -- rests on this test.
 */
class SkipLockedClaimTest extends AbstractPersistenceTest {

    private static final Duration LATCH_TIMEOUT = Duration.ofSeconds(10);

    @Test
    void twoConcurrentClaimsTakeDisjointRowsAndNeitherWaitsForTheOther() throws Exception {
        IntStream.rangeClosed(1, 4).forEach(i -> persistDelivery("evt_" + i, NOW.minusSeconds(60)));

        // Each side signals once it holds its locks, then waits for the other before committing. If
        // the claim query blocked instead of skipping, the second side would still be inside the
        // query when the first started waiting, and neither latch would ever be released.
        CountDownLatch leftHasLocked = new CountDownLatch(1);
        CountDownLatch rightHasLocked = new CountDownLatch(1);

        List<UUID> leftClaim;
        List<UUID> rightClaim;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<List<UUID>> left = pool.submit(claimTwo(leftHasLocked, rightHasLocked));
            Future<List<UUID>> right = pool.submit(claimTwo(rightHasLocked, leftHasLocked));

            leftClaim = left.get(LATCH_TIMEOUT.toSeconds() * 2, TimeUnit.SECONDS);
            rightClaim = right.get(LATCH_TIMEOUT.toSeconds() * 2, TimeUnit.SECONDS);
        }

        assertThat(leftClaim).hasSize(2);
        assertThat(rightClaim).hasSize(2);
        assertThat(leftClaim).doesNotContainAnyElementsOf(rightClaim);
        assertThat(Stream.concat(leftClaim.stream(), rightClaim.stream()).toList())
                .hasSize(4)
                .doesNotHaveDuplicates();
    }

    @Test
    void aClaimSkipsRowsThatAreNotYetDue() {
        persistDelivery("evt_due", NOW.minusSeconds(1));
        persistDelivery("evt_later", NOW.plusSeconds(600));

        List<UUID> claimed = inTransaction(() -> deliveries.lockDueDeliveryIds(NOW, 10));

        assertThat(claimed).hasSize(1);
        assertThat(deliveries.findByEventId("evt_due").orElseThrow().getId()).isEqualTo(claimed.get(0));
    }

    @Test
    void aTerminalDeliveryIsInvisibleToTheClaimQuery() {
        Delivery delivery = persistDelivery("evt_done", NOW.minusSeconds(60));
        inTransaction(() -> {
            Delivery loaded = deliveries.findById(delivery.getId()).orElseThrow();
            loaded.claim(WORKER, NOW, Duration.ofSeconds(15));
            loaded.recordSuccess(NOW);
            deliveries.save(loaded);
        });

        // This is why the attempt bound holds structurally: a finished delivery cannot be selected,
        // so no amount of scheduler ticks can produce another attempt.
        assertThat(deliveries.findById(delivery.getId()).orElseThrow().getStatus())
                .isEqualTo(DeliveryStatus.SUCCEEDED);
        assertThat(inTransaction(() -> deliveries.lockDueDeliveryIds(NOW, 10))).isEmpty();
        assertThat(deliveries.countDue(NOW)).isZero();
    }

    @Test
    void anInFlightDeliveryIsInvisibleToTheClaimQuery() {
        Delivery delivery = persistDelivery("evt_running", NOW.minusSeconds(60));
        inTransaction(() -> {
            Delivery loaded = deliveries.findById(delivery.getId()).orElseThrow();
            loaded.claim(WORKER, NOW, Duration.ofSeconds(15));
            deliveries.save(loaded);
        });

        assertThat(inTransaction(() -> deliveries.lockDueDeliveryIds(NOW, 10))).isEmpty();
    }

    @Test
    void aClaimTakesNoMoreThanTheCallerAskedFor() {
        IntStream.rangeClosed(1, 10).forEach(i -> persistDelivery("evt_" + i, NOW.minusSeconds(60)));

        // Backpressure: the scheduler asks for what its executor can run, and the rest waits in
        // PostgreSQL rather than in a queue in memory.
        assertThat(inTransaction(() -> deliveries.lockDueDeliveryIds(NOW, 3))).hasSize(3);
    }

    @Test
    void theOldestDueDeliveryIsClaimedFirst() {
        persistDelivery("evt_newest", NOW.minusSeconds(10));
        persistDelivery("evt_oldest", NOW.minusSeconds(900));
        persistDelivery("evt_middle", NOW.minusSeconds(100));

        List<UUID> claimed = inTransaction(() -> deliveries.lockDueDeliveryIds(NOW, 1));

        assertThat(claimed).containsExactly(deliveries.findByEventId("evt_oldest").orElseThrow().getId());
    }

    /**
     * Claim two rows, announce it, and hold the locks until the other side has announced too. The
     * transaction -- and therefore the locks -- lives for the whole callable.
     */
    private Callable<List<UUID>> claimTwo(CountDownLatch announce, CountDownLatch waitFor) {
        return () -> inTransaction(() -> {
            List<UUID> claimed = deliveries.lockDueDeliveryIds(NOW, 2);
            announce.countDown();
            awaitOrFail(waitFor);
            return claimed;
        });
    }

    private static void awaitOrFail(CountDownLatch latch) {
        try {
            if (!latch.await(LATCH_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "The other claim never completed within " + LATCH_TIMEOUT
                                + ". It blocked on our row locks, which means SKIP LOCKED is not in effect.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
