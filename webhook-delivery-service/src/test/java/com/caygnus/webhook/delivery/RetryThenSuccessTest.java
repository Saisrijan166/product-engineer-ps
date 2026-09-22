package com.caygnus.webhook.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.caygnus.webhook.common.model.AttemptOutcome;
import com.caygnus.webhook.common.model.DeliveryStatus;
import com.caygnus.webhook.delivery.domain.Delivery;
import com.caygnus.webhook.delivery.domain.DeliveryAttempt;
import com.caygnus.webhook.delivery.support.AbstractAcceptanceTest;
import com.caygnus.webhook.delivery.support.TestReceiver;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * <b>AC2 — temporary failure and retry.</b>
 *
 * <p>"Given the receiver temporarily fails according to the documented retry policy, when a valid
 * event is submitted, then the service records the failed attempt, retries it, and records the
 * eventual outcome."
 *
 * <p>The schedule asserted here is the one the service actually ships with -- 5s then 25s, from
 * the default profile -- not a shortened one invented for the test. That is affordable because
 * nothing waits: the clock is moved by hand, so proving what happens two and a half minutes into
 * a backoff costs the same as proving what happens immediately.
 */
class RetryThenSuccessTest extends AbstractAcceptanceTest {

    private static final Duration FIRST_BACKOFF = Duration.ofSeconds(5);
    private static final Duration SECOND_BACKOFF = Duration.ofSeconds(25);

    @Test
    void aTemporaryFailureIsRetriedUntilItSucceeds() {
        TestReceiver.failThenSucceed(2, 503);
        UUID deliveryId = createDelivery("evt_123");

        // Attempt 1: 503, which the policy calls retryable.
        assertThat(scheduler.runOnce()).isEqualTo(1);
        assertThat(reload(deliveryId).getStatus()).isEqualTo(DeliveryStatus.RETRY_SCHEDULED);

        CLOCK.advance(FIRST_BACKOFF);
        assertThat(scheduler.runOnce()).isEqualTo(1);
        assertThat(reload(deliveryId).getStatus()).isEqualTo(DeliveryStatus.RETRY_SCHEDULED);

        CLOCK.advance(SECOND_BACKOFF);
        assertThat(scheduler.runOnce()).isEqualTo(1);

        Delivery delivery = reload(deliveryId);
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.SUCCEEDED);
        assertThat(delivery.getAttemptCount()).isEqualTo(3);
        assertThat(delivery.getLastOutcome()).isEqualTo(AttemptOutcome.SUCCESS);
        assertThat(delivery.getTerminalReason()).isNull();
        assertThat(delivery.getNextAttemptAt()).isNull();
    }

    @Test
    void allThreeAttemptsAreRecordedInOrderWithWhatEachOneSaw() {
        TestReceiver.failThenSucceed(2, 503);
        UUID deliveryId = runToSuccess("evt_123");

        List<DeliveryAttempt> history = historyOf(deliveryId);

        assertThat(history).extracting(DeliveryAttempt::getAttemptNumber).containsExactly(1, 2, 3);
        assertThat(history).extracting(DeliveryAttempt::getOutcome).containsExactly(
                AttemptOutcome.RETRYABLE_FAILURE, AttemptOutcome.RETRYABLE_FAILURE, AttemptOutcome.SUCCESS);
        assertThat(history).extracting(DeliveryAttempt::getHttpStatus).containsExactly(503, 503, 200);
        // No transport error on any of them: the receiver answered every time, just unhelpfully
        // at first. Confusing "it said no" with "it did not answer" is the distinction the
        // error_class column exists to keep.
        assertThat(history).extracting(DeliveryAttempt::getErrorClass).containsOnlyNulls();
        assertThat(history).allSatisfy(attempt -> {
            assertThat(attempt.getResponseBodySnippet()).isNotBlank();
            assertThat(attempt.getWorkerId()).isNotBlank();
            assertThat(attempt.getDurationMs()).isNotNull();
        });
    }

    @Test
    void theBackoffGrowsAndEachAttemptRecordsWhatItScheduled() {
        TestReceiver.failThenSucceed(2, 503);
        UUID deliveryId = runToSuccess("evt_123");

        List<DeliveryAttempt> history = historyOf(deliveryId);

        // Attempt 1 failed at START and asked for 5 seconds; attempt 2 failed 5 seconds later and
        // asked for 25. The delay is recorded on the attempt that chose it, so the schedule can be
        // reconstructed afterwards from the history alone.
        assertThat(history.get(0).getNextAttemptAt()).isEqualTo(START.plus(FIRST_BACKOFF));
        assertThat(history.get(1).getNextAttemptAt())
                .isEqualTo(START.plus(FIRST_BACKOFF).plus(SECOND_BACKOFF));
        assertThat(history.get(2).getNextAttemptAt()).isNull();

        Duration firstGap = Duration.between(history.get(0).getStartedAt(), history.get(0).getNextAttemptAt());
        Duration secondGap = Duration.between(history.get(1).getStartedAt(), history.get(1).getNextAttemptAt());
        assertThat(firstGap).isEqualTo(FIRST_BACKOFF);
        assertThat(secondGap).isEqualTo(SECOND_BACKOFF).isGreaterThan(firstGap);
    }

    @Test
    void nothingIsRetriedBeforeItsTime() {
        TestReceiver.failThenSucceed(2, 503);
        UUID deliveryId = createDelivery("evt_123");
        scheduler.runOnce();

        // One second short of due. A scheduler that ignored next_attempt_at would burn the whole
        // attempt budget in a single tick.
        CLOCK.advance(FIRST_BACKOFF.minusSeconds(1));
        assertThat(scheduler.runOnce()).isZero();

        assertThat(TestReceiver.totalReceived()).isEqualTo(1);
        assertThat(historyOf(deliveryId)).hasSize(1);
    }

    @Test
    void everyAttemptCarriesTheSameIdempotencyKeyAndAClimbingAttemptNumber() {
        TestReceiver.failThenSucceed(2, 503);
        UUID deliveryId = runToSuccess("evt_123");

        assertThat(TestReceiver.totalReceived()).isEqualTo(3);
        // The receiver's side of at-least-once: three requests it can recognise as the same
        // delivery, and enough context to tell which retry it is looking at.
        assertThat(List.of(1, 2, 3)).allSatisfy(n -> assertThat(TestReceiver.headersOf(n))
                .containsEntry("x-idempotency-key", deliveryId.toString())
                .containsEntry("x-webhook-attempt", String.valueOf(n)));
    }

    @Test
    void aReceiverThatNamesItsOwnBackoffIsObeyed() {
        // Retry-After wins over the computed 5 seconds, and is not jittered: the receiver named a
        // time, and second-guessing it would defeat the point of asking.
        TestReceiver.alwaysFailWithRetryAfter(503, 90);
        UUID deliveryId = createDelivery("evt_123");

        scheduler.runOnce();

        DeliveryAttempt attempt = historyOf(deliveryId).get(0);
        assertThat(attempt.getRetryAfterSeconds()).isEqualTo(90);
        assertThat(attempt.getNextAttemptAt()).isEqualTo(START.plusSeconds(90));
        assertThat(reload(deliveryId).getNextAttemptAt()).isEqualTo(START.plusSeconds(90));
    }

    @Test
    void anAbsurdRetryAfterIsClampedToTheConfiguredCeiling() {
        // A header is not permission to schedule an attempt an hour out.
        TestReceiver.alwaysFailWithRetryAfter(503, 7_200);
        UUID deliveryId = createDelivery("evt_123");

        scheduler.runOnce();

        assertThat(reload(deliveryId).getNextAttemptAt()).isEqualTo(START.plus(Duration.ofMinutes(10)));
        assertThat(historyOf(deliveryId).get(0).getRetryAfterSeconds()).isEqualTo(7_200);
    }

    @Test
    void theDeliveryStaysDurablyScheduledBetweenAttempts() {
        TestReceiver.failThenSucceed(2, 503);
        UUID deliveryId = createDelivery("evt_123");
        scheduler.runOnce();

        // Straight from PostgreSQL: a retry that existed only in a worker's memory would not
        // survive a restart, and the whole design rests on it surviving one.
        assertThat(jdbc.queryForMap(
                "SELECT status, attempt_count, next_attempt_at, lease_owner FROM delivery WHERE id = ?", deliveryId))
                .containsEntry("status", "RETRY_SCHEDULED")
                .containsEntry("attempt_count", 1)
                // The lease is released the moment the attempt ends, so nothing looks in flight
                // while it is merely waiting.
                .containsEntry("lease_owner", null)
                .hasEntrySatisfying("next_attempt_at", value -> assertThat(value).isNotNull());
    }

    /** Drive {@code FAIL_N_THEN_OK(2, 503)} all the way through to its success. */
    private UUID runToSuccess(String eventId) {
        UUID deliveryId = createDelivery(eventId);
        scheduler.runOnce();
        CLOCK.advance(FIRST_BACKOFF);
        scheduler.runOnce();
        CLOCK.advance(SECOND_BACKOFF);
        scheduler.runOnce();
        return deliveryId;
    }
}
