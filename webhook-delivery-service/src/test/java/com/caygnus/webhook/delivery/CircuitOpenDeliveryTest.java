package com.caygnus.webhook.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.caygnus.webhook.common.model.AttemptOutcome;
import com.caygnus.webhook.common.model.DeliveryStatus;
import com.caygnus.webhook.common.model.ErrorClass;
import com.caygnus.webhook.delivery.domain.DeliveryAttempt;
import com.caygnus.webhook.delivery.support.AbstractAcceptanceTest;
import com.caygnus.webhook.delivery.support.TestReceiver;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * What happens once the engine decides an endpoint is not worth calling.
 *
 * <p>The threshold is lowered to two for this class. The production value of ten is proven
 * exactly in {@code EndpointHealthGateTest}; reproducing it here would mean burning through two
 * whole deliveries to set up each scenario, which would test the arithmetic of the fixture rather
 * than the behaviour of the engine.
 */
@SpringBootTest(properties = {
        "webhook.endpoint-health.failure-threshold=2",
        "webhook.endpoint-health.open-for=30s"})
class CircuitOpenDeliveryTest extends AbstractAcceptanceTest {

    private static final Duration FIRST_BACKOFF = Duration.ofSeconds(5);
    private static final Duration SECOND_BACKOFF = Duration.ofSeconds(25);
    private static final Duration OPEN_FOR = Duration.ofSeconds(30);

    @Test
    void aGatedAttemptIsRecordedAndNoRequestIsMade() {
        TestReceiver.alwaysFail(500);
        UUID deliveryId = createDelivery("evt_123");

        // Two real failures open the gate.
        scheduler.runOnce();
        CLOCK.advance(FIRST_BACKOFF);
        scheduler.runOnce();
        assertThat(TestReceiver.totalReceived()).isEqualTo(2);
        assertThat(healthGate.isOpen(TestReceiver.receiveUrl())).isTrue();

        // The third is short-circuited. Still due, still claimed, still attempted -- but the
        // request never leaves the process.
        CLOCK.advance(SECOND_BACKOFF);
        assertThat(scheduler.runOnce()).isEqualTo(1);

        assertThat(TestReceiver.totalReceived())
                .as("the gate must prevent the call, not merely record it differently")
                .isEqualTo(2);

        DeliveryAttempt gated = historyOf(deliveryId).get(2);
        assertThat(gated.getAttemptNumber()).isEqualTo(3);
        assertThat(gated.getErrorClass()).isEqualTo(ErrorClass.CIRCUIT_OPEN);
        assertThat(gated.getOutcome()).isEqualTo(AttemptOutcome.RETRYABLE_FAILURE);
        assertThat(gated.getHttpStatus()).isNull();
        assertThat(gated.getErrorMessage()).isNotBlank();
    }

    @Test
    void aGatedAttemptIsRescheduledLikeAnyOtherRetryableFailure() {
        TestReceiver.alwaysFail(500);
        UUID deliveryId = createDelivery("evt_123");
        scheduler.runOnce();
        CLOCK.advance(FIRST_BACKOFF);
        scheduler.runOnce();

        CLOCK.advance(SECOND_BACKOFF);
        scheduler.runOnce();

        // Nothing special about the scheduling: a delivery the gate refused is a delivery that
        // failed, and it waits its ordinary backoff like the rest.
        assertThat(reload(deliveryId).getStatus()).isEqualTo(DeliveryStatus.RETRY_SCHEDULED);
        assertThat(historyOf(deliveryId).get(2).getNextAttemptAt())
                .isEqualTo(CLOCK.instant().plus(Duration.ofSeconds(125)));
    }

    @Test
    void aGatedAttemptStillSpendsOneFromTheBudget() {
        TestReceiver.alwaysFail(500);
        UUID deliveryId = createDelivery("evt_123");
        scheduler.runOnce();
        CLOCK.advance(FIRST_BACKOFF);
        scheduler.runOnce();

        CLOCK.advance(SECOND_BACKOFF);
        scheduler.runOnce();

        // The honest accounting: the delivery really did fail to go out. A history with silent
        // gaps in it would be worse than one that says "we did not try, and here is why".
        assertThat(reload(deliveryId).getAttemptCount()).isEqualTo(3);
        assertThat(historyOf(deliveryId)).hasSize(3);
    }

    @Test
    void theGateLetsOneAttemptThroughOnceTheWindowHasPassed() {
        TestReceiver.alwaysFail(500);
        UUID deliveryId = createDelivery("evt_123");
        scheduler.runOnce();
        CLOCK.advance(FIRST_BACKOFF);
        scheduler.runOnce();

        // Long enough for the next backoff and for the open window to elapse.
        TestReceiver.alwaysOk();
        CLOCK.advance(SECOND_BACKOFF.plus(OPEN_FOR));
        assertThat(scheduler.runOnce()).isEqualTo(1);

        assertThat(TestReceiver.totalReceived()).isEqualTo(3);
        assertThat(reload(deliveryId).getStatus()).isEqualTo(DeliveryStatus.SUCCEEDED);
        assertThat(healthGate.isOpen(TestReceiver.receiveUrl())).isFalse();
    }

    @Test
    void oneFailingEndpointDoesNotGateAHealthyOne() {
        TestReceiver.alwaysFail(500);
        UUID failing = createDelivery("evt_failing");
        scheduler.runOnce();
        CLOCK.advance(FIRST_BACKOFF);
        scheduler.runOnce();
        assertThat(healthGate.isOpen(TestReceiver.receiveUrl())).isTrue();

        // A second endpoint, untouched by the first one's troubles. This is the whole point of
        // keying the gate by host rather than having one global breaker.
        UUID elsewhere = createDeliveryTo("evt_elsewhere", "http://127.0.0.1:" + closedPort() + "/receive");
        scheduler.runOnce();

        assertThat(healthGate.isOpen("http://127.0.0.1:1/receive")).isFalse();
        assertThat(historyOf(elsewhere)).singleElement().satisfies(attempt ->
                // A real call was made and refused, rather than being gated by the other endpoint.
                assertThat(attempt.getErrorClass()).isEqualTo(ErrorClass.CONNECTION_REFUSED));
        assertThat(reload(failing).getStatus()).isEqualTo(DeliveryStatus.RETRY_SCHEDULED);
    }

    @Test
    void aPermanentRejectionNeverGatesTheEndpoint() {
        TestReceiver.alwaysFail(422);
        createDelivery("evt_1");
        createDelivery("evt_2");
        createDelivery("evt_3");

        scheduler.runOnce();

        // Three receivers' worth of rejection, and the endpoint is demonstrably healthy: it
        // answered every time. Gating here would stop delivering because our payloads are wrong.
        assertThat(TestReceiver.totalReceived()).isEqualTo(3);
        assertThat(healthGate.isOpen(TestReceiver.receiveUrl())).isFalse();
        assertThat(healthGate.consecutiveFailures(TestReceiver.receiveUrl())).isZero();
    }

    @Test
    void aHealthyEndpointIsNeverGated() {
        TestReceiver.alwaysOk();
        for (int i = 1; i <= 20; i++) {
            createDelivery("evt_" + i);
        }

        // A tick claims only what the executor can start, so twenty deliveries take three of
        // them. The rest wait in PostgreSQL rather than in a queue in memory.
        assertThat(scheduler.runOnce()).as("bounded by max-concurrent-attempts").isEqualTo(8);
        assertThat(scheduler.runOnce()).isEqualTo(8);
        assertThat(scheduler.runOnce()).isEqualTo(4);
        assertThat(scheduler.runOnce()).isZero();

        assertThat(TestReceiver.totalReceived()).isEqualTo(20);
        assertThat(healthGate.isOpen(TestReceiver.receiveUrl())).isFalse();
    }
}
