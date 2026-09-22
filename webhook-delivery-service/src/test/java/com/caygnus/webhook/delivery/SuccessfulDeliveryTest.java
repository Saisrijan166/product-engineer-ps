package com.caygnus.webhook.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.caygnus.webhook.common.model.AttemptOutcome;
import com.caygnus.webhook.common.model.DeliveryStatus;
import com.caygnus.webhook.delivery.domain.Delivery;
import com.caygnus.webhook.delivery.domain.DeliveryAttempt;
import com.caygnus.webhook.delivery.support.AbstractAcceptanceTest;
import com.caygnus.webhook.delivery.support.TestReceiver;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * <b>AC1 — successful delivery.</b>
 *
 * <p>"Given a reachable webhook receiver returns a successful response, when a valid event is
 * submitted, then the event is delivered, marked successful, and has a recorded attempt."
 *
 * <p>The whole path runs for real: a delivery created through the use case, claimed by the real
 * scheduler with {@code SKIP LOCKED}, sent over a real socket by the real HTTP client, and
 * recorded by the real outcome transaction. Only the clock and the decision of when to tick are
 * the test's.
 */
class SuccessfulDeliveryTest extends AbstractAcceptanceTest {

    @Test
    void aReachableReceiverGetsTheEventAndTheDeliverySucceeds() {
        TestReceiver.alwaysOk();
        UUID deliveryId = createDelivery("evt_123");

        int claimed = scheduler.runOnce();

        assertThat(claimed).isEqualTo(1);

        Delivery delivery = reload(deliveryId);
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.SUCCEEDED);
        assertThat(delivery.getAttemptCount()).isEqualTo(1);
        assertThat(delivery.getLastOutcome()).isEqualTo(AttemptOutcome.SUCCESS);
        assertThat(delivery.getTerminalReason()).isNull();
        assertThat(delivery.getCompletedAt()).isEqualTo(START);
        assertThat(delivery.getFirstAttemptAt()).isEqualTo(START);
        // Nothing left to schedule and no lease still held: the delivery is genuinely finished,
        // not merely reported as such.
        assertThat(delivery.getNextAttemptAt()).isNull();
        assertThat(delivery.getLeaseOwner()).isNull();
        assertThat(delivery.getLeaseExpiresAt()).isNull();
    }

    @Test
    void exactlyOneAttemptIsRecordedAndItSaysWhatHappened() {
        TestReceiver.alwaysOk();
        UUID deliveryId = createDelivery("evt_123");

        scheduler.runOnce();

        assertThat(historyOf(deliveryId)).singleElement().satisfies(attempt -> {
            assertThat(attempt.getAttemptNumber()).isEqualTo(1);
            assertThat(attempt.getOutcome()).isEqualTo(AttemptOutcome.SUCCESS);
            assertThat(attempt.getHttpStatus()).isEqualTo(200);
            assertThat(attempt.getErrorClass()).isNull();
            assertThat(attempt.getErrorMessage()).isNull();
            assertThat(attempt.getStartedAt()).isEqualTo(START);
            assertThat(attempt.getCompletedAt()).isEqualTo(START);
            // A success schedules nothing.
            assertThat(attempt.getNextAttemptAt()).isNull();
            assertThat(attempt.getWorkerId()).isNotBlank();
        });
    }

    @Test
    void theReceiverIsCalledExactlyOnce() {
        TestReceiver.alwaysOk();
        createDelivery("evt_123");

        scheduler.runOnce();

        assertThat(TestReceiver.totalReceived()).isEqualTo(1);
    }

    @Test
    void theReceiverGetsTheHeadersItNeedsToDeduplicate() {
        TestReceiver.alwaysOk();
        UUID deliveryId = createDelivery("evt_123");

        scheduler.runOnce();

        Map<String, String> headers = TestReceiver.headersOf(1);
        assertThat(headers)
                .containsEntry("x-webhook-event-id", "evt_123")
                .containsEntry("x-webhook-delivery-id", deliveryId.toString())
                .containsEntry("x-webhook-attempt", "1")
                .containsEntry("x-webhook-event-type", "incident.created")
                .containsEntry("x-webhook-timestamp", START.toString());
        assertThat(headers.get("content-type")).contains("application/json");

        // The promise we make in place of exactly-once: a receiver can always tell a repeat of
        // this delivery from a different one, because this value never changes between attempts.
        assertThat(headers).containsEntry("x-idempotency-key", deliveryId.toString());
    }

    @Test
    void theReceiverGetsThePayloadThatWasSubmitted() throws Exception {
        TestReceiver.alwaysOk();
        createDelivery("evt_123");

        scheduler.runOnce();

        // Compared as parsed JSON rather than as text, because it is not byte-preserved: the
        // payload is stored in a jsonb column, and PostgreSQL normalises whitespace and key order
        // on the way through. What we promise a receiver is an equivalent document, not identical
        // bytes -- which is only acceptable because the signature we offer is over our own body.
        assertThat(new ObjectMapper().readValue(TestReceiver.bodyOf(1), new TypeReference<Map<String, Object>>() {
        })).containsEntry("incidentId", "inc_456").containsEntry("severity", "high");
    }

    @Test
    void aSucceededDeliveryIsNeverClaimedAgain() {
        TestReceiver.alwaysOk();
        UUID deliveryId = createDelivery("evt_123");
        scheduler.runOnce();

        // Terminal states are invisible to the claim query, so further ticks -- at any point in
        // the future -- cannot produce a second call to the receiver.
        CLOCK.advance(java.time.Duration.ofHours(1));
        assertThat(scheduler.runOnce()).isZero();
        assertThat(scheduler.runOnce()).isZero();

        assertThat(TestReceiver.totalReceived()).isEqualTo(1);
        assertThat(historyOf(deliveryId)).hasSize(1);
    }

    @Test
    void aTickWithNothingDueClaimsNothing() {
        assertThat(scheduler.runOnce()).isZero();
        assertThat(TestReceiver.totalReceived()).isZero();
    }

    @Test
    void severalDueDeliveriesAreAllSentInOneTick() {
        TestReceiver.alwaysOk();
        UUID first = createDelivery("evt_1");
        UUID second = createDelivery("evt_2");
        UUID third = createDelivery("evt_3");

        assertThat(scheduler.runOnce()).isEqualTo(3);

        assertThat(reload(first).getStatus()).isEqualTo(DeliveryStatus.SUCCEEDED);
        assertThat(reload(second).getStatus()).isEqualTo(DeliveryStatus.SUCCEEDED);
        assertThat(reload(third).getStatus()).isEqualTo(DeliveryStatus.SUCCEEDED);
        assertThat(TestReceiver.totalReceived()).isEqualTo(3);
    }

    @Test
    void aDeliveryThatIsNotYetDueIsLeftAlone() {
        TestReceiver.alwaysOk();
        UUID deliveryId = createDelivery("evt_123");
        Delivery pending = reload(deliveryId);
        pending.claim("someone-else", START, java.time.Duration.ofSeconds(15));
        pending.recordRetryableFailure(START, START.plusSeconds(600));
        deliveries.save(pending);

        assertThat(scheduler.runOnce()).isZero();
        assertThat(TestReceiver.totalReceived()).isZero();

        // ...until its time comes.
        CLOCK.advance(java.time.Duration.ofSeconds(601));
        assertThat(scheduler.runOnce()).isEqualTo(1);
        assertThat(reload(deliveryId).getStatus()).isEqualTo(DeliveryStatus.SUCCEEDED);
    }

    @Test
    void theAttemptIsDurableWithoutAnythingHeldInMemory() {
        TestReceiver.alwaysOk();
        UUID deliveryId = createDelivery("evt_123");

        scheduler.runOnce();

        // Read straight from PostgreSQL rather than through the repository's persistence context:
        // if the outcome transaction had not committed, this would not see it.
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, attempt_count, last_outcome FROM delivery WHERE id = ?", deliveryId);
        assertThat(row).containsEntry("status", "SUCCEEDED")
                .containsEntry("attempt_count", 1)
                .containsEntry("last_outcome", "SUCCESS");

        Long attemptRows = jdbc.queryForObject(
                "SELECT count(*) FROM delivery_attempt WHERE delivery_id = ?", Long.class, deliveryId);
        assertThat(attemptRows).isEqualTo(1);
    }

    @Test
    void aSuccessLeavesTheAttemptRowAsTheOnlyEvidenceNeeded() {
        TestReceiver.alwaysOk();
        UUID deliveryId = createDelivery("evt_123");

        scheduler.runOnce();

        DeliveryAttempt attempt = historyOf(deliveryId).get(0);
        assertThat(attempt.getDurationMs()).isNotNull().isGreaterThanOrEqualTo(0);
        assertThat(attempt.getResponseBodySnippet()).contains("\"status\":200");
    }
}
