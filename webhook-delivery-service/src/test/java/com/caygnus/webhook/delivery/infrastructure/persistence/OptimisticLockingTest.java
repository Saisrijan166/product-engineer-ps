package com.caygnus.webhook.delivery.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.caygnus.webhook.common.model.DeliveryStatus;
import com.caygnus.webhook.delivery.domain.Delivery;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * The second line of concurrency control, for the race the claim cannot cover.
 *
 * <p>The claim's row lock protects the moment of claiming. It says nothing about what happens
 * afterwards, when the HTTP call is deliberately outside any transaction: a worker can return from
 * a slow receiver at the same moment the reaper decides its lease has expired, and both then want
 * to write the same row. {@code @Version} is what makes one of them fail loudly instead of silently
 * overwriting the other's decision -- which, in this system, would mean a delivery attempted a sixth
 * time or an outcome quietly lost.
 */
class OptimisticLockingTest extends AbstractPersistenceTest {

    private static final Duration LEASE = Duration.ofSeconds(15);

    @Test
    void theVersionRisesWithEachCommittedChange() {
        Delivery delivery = persistDelivery("evt_123", NOW);
        assertThat(delivery.getVersion()).isZero();

        claimIn(delivery.getId());

        assertThat(deliveries.findById(delivery.getId()).orElseThrow().getVersion()).isEqualTo(1);
    }

    @Test
    void aStaleWriteIsRejectedRatherThanOverwriting() {
        Delivery delivery = persistDelivery("evt_123", NOW);

        // Both sides read the row before either writes -- the worker and the reaper, meeting.
        Delivery workersCopy = deliveries.findById(delivery.getId()).orElseThrow();
        Delivery reapersCopy = deliveries.findById(delivery.getId()).orElseThrow();
        assertThat(workersCopy.getVersion()).isEqualTo(reapersCopy.getVersion());

        reapersCopy.claim("reaper", NOW, LEASE);
        inTransaction(() -> deliveries.saveAndFlush(reapersCopy));

        workersCopy.claim("worker", NOW, LEASE);
        assertThatExceptionOfType(ObjectOptimisticLockingFailureException.class)
                .isThrownBy(() -> inTransaction(() -> deliveries.saveAndFlush(workersCopy)));
    }

    @Test
    void theLoserOfTheRaceChangesNothing() {
        Delivery delivery = persistDelivery("evt_123", NOW);
        Delivery stale = deliveries.findById(delivery.getId()).orElseThrow();

        claimIn(delivery.getId());
        Delivery afterFirstWriter = deliveries.findById(delivery.getId()).orElseThrow();

        stale.claim("loser", NOW, LEASE);
        assertThatExceptionOfType(ObjectOptimisticLockingFailureException.class)
                .isThrownBy(() -> inTransaction(() -> deliveries.saveAndFlush(stale)));

        // The winner's lease and attempt count survive intact: a lost update here would mean an
        // attempt spent with no record of who spent it.
        Delivery finalState = deliveries.findById(delivery.getId()).orElseThrow();
        assertThat(finalState.getVersion()).isEqualTo(afterFirstWriter.getVersion());
        assertThat(finalState.getLeaseOwner()).isEqualTo(WORKER);
        assertThat(finalState.getAttemptCount()).isEqualTo(1);
    }

    @Test
    void anAttemptSpentIsNeverRefundedByAConcurrentWriter() {
        Delivery delivery = persistDelivery("evt_123", NOW);
        claimIn(delivery.getId());
        Delivery stale = deliveries.findById(delivery.getId()).orElseThrow();

        // The worker finishes and records a retryable failure.
        inTransaction(() -> {
            Delivery live = deliveries.findById(delivery.getId()).orElseThrow();
            live.recordRetryableFailure(NOW, NOW.plusSeconds(5));
            deliveries.save(live);
        });

        // The reaper, holding a copy from before that, tries to reclaim the same lease.
        stale.reapExpiredLease(NOW.plus(LEASE).plusSeconds(1));
        assertThatExceptionOfType(ObjectOptimisticLockingFailureException.class)
                .isThrownBy(() -> inTransaction(() -> deliveries.saveAndFlush(stale)));

        Delivery finalState = deliveries.findById(delivery.getId()).orElseThrow();
        assertThat(finalState.getStatus()).isEqualTo(DeliveryStatus.RETRY_SCHEDULED);
        assertThat(finalState.getAttemptCount()).isEqualTo(1);
        assertThat(finalState.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(5));
    }

    @Test
    void theClaimStateSurvivesARoundTripThroughPostgres() {
        Delivery delivery = persistDelivery("evt_123", NOW);
        claimIn(delivery.getId());

        Delivery reloaded = deliveries.findById(delivery.getId()).orElseThrow();

        assertThat(reloaded.getStatus()).isEqualTo(DeliveryStatus.IN_FLIGHT);
        assertThat(reloaded.getAttemptCount()).isEqualTo(1);
        assertThat(reloaded.getLeaseOwner()).isEqualTo(WORKER);
        assertThat(reloaded.getLeaseExpiresAt()).isEqualTo(NOW.plus(LEASE));
        assertThat(reloaded.getFirstAttemptAt()).isEqualTo(NOW);
        assertThat(reloaded.getNextAttemptAt()).isNull();
    }

    private void claimIn(UUID deliveryId) {
        claimIn(deliveryId, NOW);
    }

    private void claimIn(UUID deliveryId, Instant now) {
        inTransaction(() -> {
            Delivery loaded = deliveries.findById(deliveryId).orElseThrow();
            loaded.claim(WORKER, now, LEASE);
            deliveries.save(loaded);
        });
    }
}
