package com.caygnus.webhook.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.caygnus.webhook.common.model.AttemptOutcome;
import com.caygnus.webhook.common.model.DeliveryStatus;
import com.caygnus.webhook.common.model.TerminalReason;
import com.caygnus.webhook.delivery.domain.Delivery;
import com.caygnus.webhook.delivery.domain.DeliveryAttempt;
import com.caygnus.webhook.delivery.support.AbstractAcceptanceTest;
import com.caygnus.webhook.delivery.support.TestReceiver;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The other way a delivery can stop: a response no retry would fix.
 *
 * <p>Not one of the five acceptance criteria, but the point of classifying at all. Without this
 * branch, "bounded retry" would still be true and completely useless -- a 422 would be attempted
 * five times over twelve minutes, occupying a worker each time, to be told the same thing on each
 * one. Stopping at the first attempt is what makes the retry policy a policy rather than a limit.
 */
class PermanentFailureTest extends AbstractAcceptanceTest {

    @Test
    void anUnprocessableEntityStopsAfterASingleAttempt() {
        TestReceiver.alwaysFail(422);
        UUID deliveryId = createDelivery("evt_bad");

        assertThat(scheduler.runOnce()).isEqualTo(1);

        Delivery delivery = reload(deliveryId);
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.FAILED_PERMANENT);
        assertThat(delivery.getTerminalReason()).isEqualTo(TerminalReason.NON_RETRYABLE_RESPONSE);
        assertThat(delivery.getLastOutcome()).isEqualTo(AttemptOutcome.PERMANENT_FAILURE);
        assertThat(delivery.getCompletedAt()).isNotNull();
        assertThat(delivery.getNextAttemptAt()).isNull();
        assertThat(delivery.getLeaseOwner()).isNull();

        assertThat(historyOf(deliveryId)).singleElement().satisfies(attempt -> {
            assertThat(attempt.getOutcome()).isEqualTo(AttemptOutcome.PERMANENT_FAILURE);
            assertThat(attempt.getHttpStatus()).isEqualTo(422);
            assertThat(attempt.getNextAttemptAt()).isNull();
        });
    }

    @Test
    void theAttemptBudgetIsLeftUnspent() {
        TestReceiver.alwaysFail(422);
        UUID deliveryId = createDelivery("evt_bad");

        scheduler.runOnce();

        // One of five used. Stopping early is the saving; a delivery that burned all five on a
        // response that will never change would cost four pointless calls to the receiver.
        Delivery delivery = reload(deliveryId);
        assertThat(delivery.getAttemptCount()).isEqualTo(1);
        assertThat(delivery.getMaxAttempts()).isEqualTo(5);
        assertThat(delivery.hasAttemptsRemaining()).isTrue();
    }

    @Test
    void itIsNeverPickedUpAgain() {
        TestReceiver.alwaysFail(422);
        UUID deliveryId = createDelivery("evt_bad");
        scheduler.runOnce();

        CLOCK.advance(Duration.ofDays(1));
        assertThat(scheduler.runOnce()).isZero();

        assertThat(TestReceiver.totalReceived()).isEqualTo(1);
        assertThat(historyOf(deliveryId)).hasSize(1);
    }

    @ParameterizedTest(name = "HTTP {0} is not retried")
    @ValueSource(ints = {400, 401, 403, 404, 405, 409, 410, 413, 422, 501})
    void everyNonRetryableStatusStopsImmediately(int status) {
        TestReceiver.alwaysFail(status);
        UUID deliveryId = createDelivery("evt_" + status);

        scheduler.runOnce();

        assertThat(reload(deliveryId).getStatus()).isEqualTo(DeliveryStatus.FAILED_PERMANENT);
        assertThat(historyOf(deliveryId)).hasSize(1);
    }

    @Test
    void aPermanentFailureIsDistinguishableFromAnExhaustedOne() {
        TestReceiver.alwaysFail(404);
        UUID deliveryId = createDelivery("evt_gone");

        scheduler.runOnce();

        // Both are terminal failures, but they mean different things to whoever is on call: one
        // is a misconfigured endpoint, the other an endpoint that was down for twelve minutes.
        // The dead-letter view lists only the second.
        assertThat(reload(deliveryId).getTerminalReason()).isEqualTo(TerminalReason.NON_RETRYABLE_RESPONSE);
        assertThat(queries.findByStatus(DeliveryStatus.FAILED_EXHAUSTED, 50)).isEmpty();
        assertThat(queries.findByStatus(DeliveryStatus.FAILED_PERMANENT, 50)).hasSize(1);
    }

    @Test
    void theResponseBodyIsKeptSoTheRejectionCanBeDiagnosed() {
        TestReceiver.alwaysFail(422);
        UUID deliveryId = createDelivery("evt_bad");

        scheduler.runOnce();

        DeliveryAttempt attempt = historyOf(deliveryId).get(0);
        assertThat(attempt.getResponseBodySnippet()).isNotBlank().contains("422");
        assertThat(attempt.getErrorClass()).isNull();
    }
}
