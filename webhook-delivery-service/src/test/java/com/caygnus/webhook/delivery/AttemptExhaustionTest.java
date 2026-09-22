package com.caygnus.webhook.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.caygnus.webhook.common.api.DeliveryView;
import com.caygnus.webhook.common.model.AttemptOutcome;
import com.caygnus.webhook.common.model.DeliveryStatus;
import com.caygnus.webhook.common.model.TerminalReason;
import com.caygnus.webhook.delivery.domain.Delivery;
import com.caygnus.webhook.delivery.domain.DeliveryAttempt;
import com.caygnus.webhook.delivery.support.AbstractAcceptanceTest;
import com.caygnus.webhook.delivery.support.TestReceiver;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * <b>AC3 — bounded failure.</b>
 *
 * <p>"Given the receiver continues to fail, when the configured attempt limit is reached, then
 * delivery stops, the final failed state is visible, and attempts do not continue forever."
 *
 * <p>The last of those three is the one worth being careful about, because it is a claim about
 * something <em>not</em> happening. Asserting it once proves little; what these tests lean on is
 * that it holds structurally -- a terminal delivery does not match the claim query's status
 * filter, so no tick at any future time can select it. That is checked here by running the
 * scheduler again, hours later, and watching nothing happen.
 */
class AttemptExhaustionTest extends AbstractAcceptanceTest {

    /** The default profile's schedule: the gap after attempts 1, 2, 3 and 4. */
    private static final List<Duration> BACKOFF_AFTER_EACH_ATTEMPT = List.of(
            Duration.ofSeconds(5),
            Duration.ofSeconds(25),
            Duration.ofSeconds(125),
            Duration.ofMinutes(10));

    private static final int MAX_ATTEMPTS = 5;

    @Test
    void aReceiverThatNeverRecoversStopsAfterExactlyFiveAttempts() {
        TestReceiver.alwaysFail(500);
        UUID deliveryId = exhaust("evt_dead");

        Delivery delivery = reload(deliveryId);
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.FAILED_EXHAUSTED);
        assertThat(delivery.getAttemptCount()).isEqualTo(MAX_ATTEMPTS);
        assertThat(delivery.getTerminalReason()).isEqualTo(TerminalReason.ATTEMPTS_EXHAUSTED);
        assertThat(delivery.getLastOutcome()).isEqualTo(AttemptOutcome.RETRYABLE_FAILURE);
        assertThat(delivery.getCompletedAt()).isNotNull();
        // Nothing is scheduled and no lease is held: the delivery is finished, not merely stuck.
        assertThat(delivery.getNextAttemptAt()).isNull();
        assertThat(delivery.getLeaseOwner()).isNull();

        assertThat(historyOf(deliveryId)).hasSize(MAX_ATTEMPTS);
        assertThat(TestReceiver.totalReceived()).isEqualTo(MAX_ATTEMPTS);
    }

    @Test
    void noSixthAttemptIsEverProduced() {
        TestReceiver.alwaysFail(500);
        UUID deliveryId = exhaust("evt_dead");

        // Days later, and repeatedly. A terminal delivery is invisible to the claim query, so
        // there is nothing for a tick to find however often it runs.
        for (int tick = 0; tick < 5; tick++) {
            CLOCK.advance(Duration.ofHours(6));
            assertThat(scheduler.runOnce()).isZero();
        }

        assertThat(historyOf(deliveryId)).hasSize(MAX_ATTEMPTS);
        assertThat(TestReceiver.totalReceived()).isEqualTo(MAX_ATTEMPTS);
        assertThat(reload(deliveryId).getAttemptCount()).isEqualTo(MAX_ATTEMPTS);
    }

    @Test
    void everyFailedAttemptIsRecordedWithTheStatusThatCausedIt() {
        TestReceiver.alwaysFail(500);
        UUID deliveryId = exhaust("evt_dead");

        List<DeliveryAttempt> history = historyOf(deliveryId);

        assertThat(history).extracting(DeliveryAttempt::getAttemptNumber).containsExactly(1, 2, 3, 4, 5);
        assertThat(history).extracting(DeliveryAttempt::getOutcome)
                .containsOnly(AttemptOutcome.RETRYABLE_FAILURE);
        assertThat(history).extracting(DeliveryAttempt::getHttpStatus).containsOnly(500);
        assertThat(history).allSatisfy(attempt -> assertThat(attempt.getWorkerId()).isNotBlank());
    }

    @Test
    void theRecordedScheduleShowsTheBackoffGrowingAndThenBeingCapped() {
        TestReceiver.alwaysFail(500);
        UUID deliveryId = exhaust("evt_dead");

        List<DeliveryAttempt> history = historyOf(deliveryId);
        List<Duration> scheduledGaps = history.stream()
                .filter(attempt -> attempt.getNextAttemptAt() != null)
                .map(attempt -> Duration.between(attempt.getStartedAt(), attempt.getNextAttemptAt()))
                .toList();

        // 5s, 25s, 125s, then 600s rather than 625s -- the cap doing its job. The fifth attempt
        // schedules nothing at all, which is what makes the bound visible in the history.
        assertThat(scheduledGaps).isEqualTo(BACKOFF_AFTER_EACH_ATTEMPT);
        assertThat(history.get(MAX_ATTEMPTS - 1).getNextAttemptAt()).isNull();
    }

    @Test
    void theExhaustedDeliveryTurnsUpInTheDeadLetterView() {
        TestReceiver.alwaysFail(500);
        UUID deliveryId = exhaust("evt_dead");
        TestReceiver.alwaysOk();
        UUID healthy = createDelivery("evt_fine");
        scheduler.runOnce();

        // The dead-letter view is an ordinary query rather than a separate store, which is what
        // lets a failed delivery still be joined to its own attempt history.
        List<DeliveryView> deadLettered = queries.findByStatus(DeliveryStatus.FAILED_EXHAUSTED, 50);

        assertThat(deadLettered).extracting(DeliveryView::deliveryId).containsExactly(deliveryId);
        assertThat(deadLettered).extracting(DeliveryView::terminalReason)
                .containsExactly(TerminalReason.ATTEMPTS_EXHAUSTED);
        assertThat(deadLettered).extracting(DeliveryView::attemptCount).containsExactly(MAX_ATTEMPTS);
        assertThat(queries.findById(healthy).orElseThrow().status()).isEqualTo(DeliveryStatus.SUCCEEDED);
    }

    @Test
    void theFinalStateIsDurableRatherThanAnArtefactOfTheRunningProcess() {
        TestReceiver.alwaysFail(500);
        UUID deliveryId = exhaust("evt_dead");

        assertThat(jdbc.queryForMap(
                "SELECT status, terminal_reason, attempt_count, next_attempt_at FROM delivery WHERE id = ?",
                deliveryId))
                .containsEntry("status", "FAILED_EXHAUSTED")
                .containsEntry("terminal_reason", "ATTEMPTS_EXHAUSTED")
                .containsEntry("attempt_count", MAX_ATTEMPTS)
                .containsEntry("next_attempt_at", null);
    }

    @Test
    void oneDoomedDeliveryDoesNotStopOthersFromSucceeding() {
        TestReceiver.alwaysFail(500);
        UUID doomed = exhaust("evt_dead");

        TestReceiver.alwaysOk();
        UUID healthy = createDelivery("evt_fine");
        assertThat(scheduler.runOnce()).isEqualTo(1);

        assertThat(reload(healthy).getStatus()).isEqualTo(DeliveryStatus.SUCCEEDED);
        assertThat(reload(doomed).getStatus()).isEqualTo(DeliveryStatus.FAILED_EXHAUSTED);
    }

    /**
     * Run a delivery through its whole attempt budget, stepping the clock over each backoff in
     * turn. Every tick is asserted to claim exactly one delivery, so a schedule that drifted from
     * the policy would fail here rather than quietly changing what this test covers.
     */
    private UUID exhaust(String eventId) {
        UUID deliveryId = createDelivery(eventId);
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            assertThat(scheduler.runOnce())
                    .as("attempt %d of %d should have been claimed", attempt, MAX_ATTEMPTS)
                    .isEqualTo(1);
            if (attempt < MAX_ATTEMPTS) {
                CLOCK.advance(BACKOFF_AFTER_EACH_ATTEMPT.get(attempt - 1));
            }
        }
        return deliveryId;
    }
}
