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
 * Failures where the receiver never answers at all.
 *
 * <p>These are the ones a naive implementation gets wrong, because there is no status code to
 * classify -- only an exception, thrown from somewhere inside a client library that wraps it two
 * or three deep. Getting them wrong in either direction is costly: treat a refused connection as
 * permanent and a receiver's ten-second restart loses every event in it; treat a bad certificate
 * as retryable and four more attempts go out to prove what the first one already established.
 *
 * <p>The response timeout is shortened to 300ms for this class alone. That is not a shortcut
 * around the no-sleeping rule: the receiver stalls deliberately, and what is being shortened is
 * how long <em>the engine</em> is configured to tolerate, which is the behaviour under test.
 */
@SpringBootTest(properties = "webhook.delivery.response-timeout=300ms")
class TransportFailureRetryTest extends AbstractAcceptanceTest {

    private static final Duration FIRST_BACKOFF = Duration.ofSeconds(5);

    @Test
    void aRefusedConnectionIsRecordedAsSuchAndRetried() {
        UUID deliveryId = createDeliveryTo("evt_refused", "http://127.0.0.1:" + closedPort() + "/receive");

        assertThat(scheduler.runOnce()).isEqualTo(1);

        assertThat(historyOf(deliveryId)).singleElement().satisfies(attempt -> {
            assertThat(attempt.getOutcome()).isEqualTo(AttemptOutcome.RETRYABLE_FAILURE);
            assertThat(attempt.getErrorClass()).isEqualTo(ErrorClass.CONNECTION_REFUSED);
            // No status, because nothing answered. The pair of columns is mutually exclusive by
            // design, and that is how a reader tells "it said no" from "it was not there".
            assertThat(attempt.getHttpStatus()).isNull();
            assertThat(attempt.getErrorMessage()).isNotBlank();
            assertThat(attempt.getResponseBodySnippet()).isNull();
        });
        assertThat(reload(deliveryId).getStatus()).isEqualTo(DeliveryStatus.RETRY_SCHEDULED);
    }

    @Test
    void aRefusedConnectionBacksOffOnTheSameScheduleAsAFailedResponse() {
        UUID deliveryId = createDeliveryTo("evt_refused", "http://127.0.0.1:" + closedPort() + "/receive");

        scheduler.runOnce();

        assertThat(reload(deliveryId).getNextAttemptAt()).isEqualTo(START.plus(FIRST_BACKOFF));
        assertThat(scheduler.runOnce()).as("not due yet").isZero();

        CLOCK.advance(FIRST_BACKOFF);
        assertThat(scheduler.runOnce()).isEqualTo(1);
        assertThat(historyOf(deliveryId)).hasSize(2);
    }

    @Test
    void anUnreachableEndpointIsStillBounded() {
        UUID deliveryId = createDeliveryTo("evt_refused", "http://127.0.0.1:" + closedPort() + "/receive");

        // A receiver that never comes back must not be retried forever any more than one that
        // answers badly.
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThat(scheduler.runOnce()).isEqualTo(1);
            CLOCK.advance(Duration.ofHours(1));
        }

        assertThat(reload(deliveryId).getStatus()).isEqualTo(DeliveryStatus.FAILED_EXHAUSTED);
        assertThat(historyOf(deliveryId)).hasSize(5);
        assertThat(scheduler.runOnce()).isZero();
    }

    @Test
    void aReceiverThatNeverAnswersInTimeIsATimeoutAndIsRetried() {
        // Stalls for two seconds against a 300ms tolerance.
        TestReceiver.stallFor(2_000);
        UUID deliveryId = createDelivery("evt_slow");

        assertThat(scheduler.runOnce()).isEqualTo(1);

        assertThat(historyOf(deliveryId)).singleElement().satisfies(attempt -> {
            assertThat(attempt.getOutcome()).isEqualTo(AttemptOutcome.RETRYABLE_FAILURE);
            assertThat(attempt.getErrorClass()).isEqualTo(ErrorClass.READ_TIMEOUT);
            assertThat(attempt.getHttpStatus()).isNull();
        });
        assertThat(reload(deliveryId).getStatus()).isEqualTo(DeliveryStatus.RETRY_SCHEDULED);
    }

    @Test
    void aTimedOutAttemptMayWellHaveReachedTheReceiver() {
        TestReceiver.stallFor(2_000);
        UUID deliveryId = createDelivery("evt_slow");

        scheduler.runOnce();

        // This is the duplicate-delivery case from ARCHITECTURE.md 9.4, made visible: we gave up
        // and will retry, but the receiver did get the request and will get it again. It is
        // precisely why the idempotency key is stable across attempts instead of per-attempt --
        // we cannot promise exactly-once, so we make a repeat recognisable.
        assertThat(TestReceiver.totalReceived()).isEqualTo(1);
        assertThat(TestReceiver.headersOf(1)).containsEntry("x-idempotency-key", deliveryId.toString());

        CLOCK.advance(FIRST_BACKOFF);
        TestReceiver.alwaysOk();
        scheduler.runOnce();

        assertThat(reload(deliveryId).getStatus()).isEqualTo(DeliveryStatus.SUCCEEDED);
        assertThat(TestReceiver.headersOf(2))
                .containsEntry("x-idempotency-key", deliveryId.toString())
                .containsEntry("x-webhook-attempt", "2");
    }

    @Test
    void anUnresolvableHostIsRecordedAsADnsFailure() {
        UUID deliveryId = createDeliveryTo(
                "evt_dns", "http://no-such-host.invalid.caygnus-webhook-test/receive");

        scheduler.runOnce();

        DeliveryAttempt attempt = historyOf(deliveryId).get(0);
        assertThat(attempt.getErrorClass()).isEqualTo(ErrorClass.DNS_FAILURE);
        // Retryable: a name that does not resolve now may resolve once DNS catches up, and the
        // bound stops it mattering if it never does.
        assertThat(attempt.getOutcome()).isEqualTo(AttemptOutcome.RETRYABLE_FAILURE);
        assertThat(reload(deliveryId).getStatus()).isEqualTo(DeliveryStatus.RETRY_SCHEDULED);
    }

    @Test
    void aMalformedTargetIsPermanentBecauseNoRetryWouldFixIt() {
        UUID deliveryId = createDeliveryTo("evt_broken", "not-even-a-url");

        scheduler.runOnce();

        assertThat(historyOf(deliveryId)).singleElement().satisfies(attempt -> {
            assertThat(attempt.getErrorClass())
                    .as("recorded message was: %s", attempt.getErrorMessage())
                    .isEqualTo(ErrorClass.MALFORMED_URL);
            assertThat(attempt.getOutcome()).isEqualTo(AttemptOutcome.PERMANENT_FAILURE);
        });
        assertThat(reload(deliveryId).getStatus()).isEqualTo(DeliveryStatus.FAILED_PERMANENT);
    }
}
