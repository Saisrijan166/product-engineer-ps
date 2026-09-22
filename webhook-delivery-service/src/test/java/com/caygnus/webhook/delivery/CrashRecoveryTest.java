package com.caygnus.webhook.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.caygnus.webhook.common.model.DeliveryStatus;
import com.caygnus.webhook.common.model.TerminalReason;
import com.caygnus.webhook.delivery.application.LeaseReaper;
import com.caygnus.webhook.delivery.domain.Delivery;
import com.caygnus.webhook.delivery.support.AbstractAcceptanceTest;
import com.caygnus.webhook.delivery.support.TestReceiver;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * What happens to a delivery whose worker never came back.
 *
 * <p>Every test here stages the same situation: a delivery marked {@code IN_FLIGHT}, holding a
 * lease, with no process behind it. That is exactly the state a {@code kill -9} between the claim
 * transaction and the outcome transaction leaves behind, and it is invisible to everything else in
 * the system -- the claim query selects only {@code PENDING} and {@code RETRY_SCHEDULED}, which is
 * the same filter that stops finished deliveries being retried. Without the reaper such a delivery
 * would simply stop, silently, forever.
 *
 * <p>The lease is 15 seconds here: the 5-second response timeout plus 10 seconds of slack. The
 * slack is why a merely slow attempt cannot be reaped out from under itself -- the request must
 * already have timed out before the lease is even eligible.
 */
class CrashRecoveryTest extends AbstractAcceptanceTest {

    private static final Duration LEASE = Duration.ofSeconds(15);
    private static final Duration PAST_LEASE = LEASE.plusSeconds(1);
    private static final int MAX_ATTEMPTS = 5;

    @Autowired
    private LeaseReaper reaper;

    // ------------------------------------------------- branch 1: attempts remain

    @Test
    void anAbandonedDeliveryWithAttemptsLeftIsPutBackInTheQueue() {
        UUID deliveryId = abandonMidAttempt("evt_crashed");

        CLOCK.advance(PAST_LEASE);
        assertThat(reaper.runOnce()).isEqualTo(1);

        Delivery delivery = reload(deliveryId);
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.RETRY_SCHEDULED);
        // Due now, not after a backoff: the wait has already happened, in the shape of a lease
        // nobody was honouring.
        assertThat(delivery.getNextAttemptAt()).isEqualTo(CLOCK.instant());
        assertThat(delivery.getLeaseOwner()).isNull();
        assertThat(delivery.getLeaseExpiresAt()).isNull();
    }

    @Test
    void theAttemptItWasHoldingIsNotRefunded() {
        UUID deliveryId = abandonMidAttempt("evt_crashed");

        CLOCK.advance(PAST_LEASE);
        reaper.runOnce();

        // The dead worker may well have reached the receiver before it died -- we have no way to
        // know. Counting the attempt anyway is what keeps the bound honest across a crash; giving
        // it back would let a process that crashes reliably retry forever.
        assertThat(reload(deliveryId).getAttemptCount()).isEqualTo(1);
    }

    @Test
    void aReclaimedDeliveryIsThenDeliveredNormally() {
        TestReceiver.alwaysOk();
        UUID deliveryId = abandonMidAttempt("evt_crashed");

        CLOCK.advance(PAST_LEASE);
        reaper.runOnce();

        // The whole point: recovery hands the delivery back to the ordinary path, which knows
        // nothing about crashes.
        assertThat(scheduler.runOnce()).isEqualTo(1);
        assertThat(reload(deliveryId).getStatus()).isEqualTo(DeliveryStatus.SUCCEEDED);
        assertThat(reload(deliveryId).getAttemptCount()).isEqualTo(2);
        assertThat(TestReceiver.totalReceived()).isEqualTo(1);
    }

    // ------------------------------------------- branch 2: the budget is spent

    @Test
    void anAbandonedDeliveryOnItsLastAttemptIsFinishedRatherThanRequeued() {
        UUID deliveryId = abandonOnFinalAttempt("evt_crashed_last");

        CLOCK.advance(PAST_LEASE);
        assertThat(reaper.runOnce()).isEqualTo(1);

        Delivery delivery = reload(deliveryId);
        // Re-queueing this would put it in a state the claim query can see but the domain would
        // refuse to claim, and it would sit there being looked at forever.
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.FAILED_EXHAUSTED);
        assertThat(delivery.getTerminalReason()).isEqualTo(TerminalReason.ATTEMPTS_EXHAUSTED);
        assertThat(delivery.getAttemptCount()).isEqualTo(MAX_ATTEMPTS);
        assertThat(delivery.getCompletedAt()).isNotNull();
        assertThat(delivery.getNextAttemptAt()).isNull();
        assertThat(delivery.getLeaseOwner()).isNull();
    }

    @Test
    void aCrashCanNeverPushADeliveryPastTheBound() {
        UUID deliveryId = abandonOnFinalAttempt("evt_crashed_last");

        CLOCK.advance(PAST_LEASE);
        reaper.runOnce();

        CLOCK.advance(Duration.ofDays(1));
        assertThat(scheduler.runOnce()).isZero();
        assertThat(reaper.runOnce()).isZero();
        assertThat(reload(deliveryId).getAttemptCount()).isEqualTo(MAX_ATTEMPTS);
    }

    // ------------------------------------------------------ what it leaves alone

    @Test
    void aLeaseThatHasNotExpiredIsLeftAlone() {
        UUID deliveryId = abandonMidAttempt("evt_running");

        // One second short. Reaping here would mean a second worker calling the receiver while
        // the first is still waiting on it -- a duplicate we could have avoided.
        CLOCK.advance(LEASE.minusSeconds(1));
        assertThat(reaper.runOnce()).isZero();
        assertThat(reload(deliveryId).getStatus()).isEqualTo(DeliveryStatus.IN_FLIGHT);
    }

    @Test
    void deliveriesThatAreNotInFlightAreNotTheReapersBusiness() {
        TestReceiver.alwaysOk();
        UUID succeeded = createDelivery("evt_done");
        scheduler.runOnce();
        UUID waiting = createDelivery("evt_waiting");

        CLOCK.advance(Duration.ofHours(1));
        assertThat(reaper.runOnce()).isZero();

        assertThat(reload(succeeded).getStatus()).isEqualTo(DeliveryStatus.SUCCEEDED);
        assertThat(reload(waiting).getStatus()).isEqualTo(DeliveryStatus.PENDING);
    }

    @Test
    void aQuietSweepDoesNothingAndSaysNothing() {
        assertThat(reaper.runOnce()).isZero();
    }

    // ----------------------------------------------------------- startup sweep

    @Test
    void theStartupSweepRecoversEverythingStrandedByThePreviousProcess() {
        TestReceiver.alwaysOk();
        UUID first = abandonMidAttempt("evt_crashed_1");
        UUID second = abandonMidAttempt("evt_crashed_2");
        UUID spent = abandonOnFinalAttempt("evt_crashed_3");

        CLOCK.advance(PAST_LEASE);
        reaper.recoverOnStartup();

        // The restart case: whatever the dead instance was holding is picked up before this one
        // starts claiming work of its own, so a restart is a pause rather than a gap.
        assertThat(reload(first).getStatus()).isEqualTo(DeliveryStatus.RETRY_SCHEDULED);
        assertThat(reload(second).getStatus()).isEqualTo(DeliveryStatus.RETRY_SCHEDULED);
        assertThat(reload(spent).getStatus()).isEqualTo(DeliveryStatus.FAILED_EXHAUSTED);

        assertThat(scheduler.runOnce()).isEqualTo(2);
        assertThat(reload(first).getStatus()).isEqualTo(DeliveryStatus.SUCCEEDED);
        assertThat(reload(second).getStatus()).isEqualTo(DeliveryStatus.SUCCEEDED);
    }

    @Test
    void theStartupSweepIsSafeWhenThereIsNothingToRecover() {
        // The boring case, and the one that runs on almost every start.
        reaper.recoverOnStartup();

        assertThat(deliveries.count()).isZero();
    }

    @Test
    void recoveryIsDurableRatherThanAnArtefactOfTheRunningProcess() {
        UUID deliveryId = abandonMidAttempt("evt_crashed");

        CLOCK.advance(PAST_LEASE);
        reaper.runOnce();

        assertThat(jdbc.queryForMap(
                "SELECT status, attempt_count, lease_owner, lease_expires_at FROM delivery WHERE id = ?",
                deliveryId))
                .containsEntry("status", "RETRY_SCHEDULED")
                .containsEntry("attempt_count", 1)
                .containsEntry("lease_owner", null)
                .containsEntry("lease_expires_at", null);
    }

    // --------------------------------------------------------------- fixtures

    /**
     * A delivery claimed and then abandoned: exactly what a {@code kill -9} between the claim
     * transaction and the outcome transaction leaves in the table.
     */
    private UUID abandonMidAttempt(String eventId) {
        UUID deliveryId = createDelivery(eventId);
        inTransaction.executeWithoutResult(status -> {
            Delivery delivery = deliveries.findById(deliveryId).orElseThrow();
            delivery.claim("dead-worker:1:ffff", CLOCK.instant(), LEASE);
            deliveries.save(delivery);
        });
        return deliveryId;
    }

    /** The same, but with the attempt budget already down to its last one. */
    private UUID abandonOnFinalAttempt(String eventId) {
        UUID deliveryId = createDelivery(eventId);
        inTransaction.executeWithoutResult(status -> {
            Delivery delivery = deliveries.findById(deliveryId).orElseThrow();
            for (int attempt = 1; attempt < MAX_ATTEMPTS; attempt++) {
                delivery.claim("dead-worker:1:ffff", CLOCK.instant(), LEASE);
                delivery.recordRetryableFailure(CLOCK.instant(), CLOCK.instant());
            }
            // Claimed for the fifth and final time, and then the process died.
            delivery.claim("dead-worker:1:ffff", CLOCK.instant(), LEASE);
            deliveries.save(delivery);
        });
        return deliveryId;
    }
}
