package com.caygnus.webhook.ingest.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.caygnus.webhook.common.api.AttemptView;
import com.caygnus.webhook.common.api.DeliveryView;
import com.caygnus.webhook.common.model.AttemptOutcome;
import com.caygnus.webhook.common.model.DeliveryStatus;
import com.caygnus.webhook.common.model.ErrorClass;
import com.caygnus.webhook.common.model.TerminalReason;
import com.caygnus.webhook.ingest.support.AbstractIngestTest;
import com.caygnus.webhook.ingest.support.StubDeliveryService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * <b>AC5 — inspectable history.</b>
 *
 * <p>"Given one or more delivery attempts occurred, when the reviewer inspects the event, then the
 * current state and ordered attempt history are available."
 *
 * <p>One request, across both services. A reviewer holding an event id should not have to know
 * that a delivery service exists, look up its internal endpoint, and join the two by hand -- so
 * the ingest service does the join and answers with the whole story: what was submitted, how the
 * handoff went, and every attempt in the order it happened.
 */
class EventHistoryApiTest extends AbstractIngestTest {

    private static final UUID DELIVERY_ID = UUID.fromString("8f2c1b4e-0000-4000-8000-000000000001");

    @Test
    void theEventItsDispatchAndItsDeliveryComeBackTogether() throws Exception {
        StubDeliveryService.acceptingAs(DELIVERY_ID);
        StubDeliveryService.holdingDelivery(deliveryWithThreeAttempts());
        mockMvc.perform(postEvent("evt_123"));
        dispatcher.runOnce();

        mockMvc.perform(get("/api/v1/events/{eventId}", "evt_123"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                // The event, as this service holds it.
                .andExpect(jsonPath("$.eventId").value("evt_123"))
                .andExpect(jsonPath("$.type").value("incident.created"))
                .andExpect(jsonPath("$.occurredAt").exists())
                .andExpect(jsonPath("$.receivedAt").exists())
                .andExpect(jsonPath("$.duplicateSubmissionCount").value(0))
                // The handoff.
                .andExpect(jsonPath("$.dispatch.status").value("DISPATCHED"))
                .andExpect(jsonPath("$.dispatch.attempts").value(0))
                // And what the delivery service made of it.
                .andExpect(jsonPath("$.delivery.deliveryId").value(DELIVERY_ID.toString()))
                .andExpect(jsonPath("$.delivery.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.delivery.attemptCount").value(3))
                .andExpect(jsonPath("$.delivery.maxAttempts").value(5))
                .andExpect(jsonPath("$.deliveryLookupError").doesNotExist());
    }

    @Test
    void theAttemptHistoryIsOrderedAndSaysWhatHappenedEachTime() throws Exception {
        StubDeliveryService.holdingDelivery(deliveryWithThreeAttempts());
        mockMvc.perform(postEvent("evt_123"));
        dispatcher.runOnce();

        mockMvc.perform(get("/api/v1/events/{eventId}", "evt_123"))
                .andExpect(jsonPath("$.delivery.attempts.length()").value(3))
                // Oldest first, so the sequence reads as the story it is.
                .andExpect(jsonPath("$.delivery.attempts[0].attemptNumber").value(1))
                .andExpect(jsonPath("$.delivery.attempts[1].attemptNumber").value(2))
                .andExpect(jsonPath("$.delivery.attempts[2].attemptNumber").value(3))
                // A receiver that said no...
                .andExpect(jsonPath("$.delivery.attempts[0].outcome").value("RETRYABLE_FAILURE"))
                .andExpect(jsonPath("$.delivery.attempts[0].httpStatus").value(503))
                .andExpect(jsonPath("$.delivery.attempts[0].responseBodySnippet").value("service unavailable"))
                .andExpect(jsonPath("$.delivery.attempts[0].nextAttemptAt").exists())
                // ...then one that did not answer at all...
                .andExpect(jsonPath("$.delivery.attempts[1].errorClass").value("READ_TIMEOUT"))
                .andExpect(jsonPath("$.delivery.attempts[1].httpStatus").doesNotExist())
                // ...and finally one that worked.
                .andExpect(jsonPath("$.delivery.attempts[2].outcome").value("SUCCESS"))
                .andExpect(jsonPath("$.delivery.attempts[2].httpStatus").value(200))
                .andExpect(jsonPath("$.delivery.attempts[2].durationMs").value(87))
                .andExpect(jsonPath("$.delivery.attempts[2].workerId").value("delivery-1:7:a3f"));
    }

    @Test
    void aTerminalFailureExplainsItself() throws Exception {
        StubDeliveryService.holdingDelivery(exhaustedDelivery());
        mockMvc.perform(postEvent("evt_dead"));
        dispatcher.runOnce();

        mockMvc.perform(get("/api/v1/events/{eventId}", "evt_dead"))
                .andExpect(jsonPath("$.delivery.status").value("FAILED_EXHAUSTED"))
                .andExpect(jsonPath("$.delivery.terminalReason").value("ATTEMPTS_EXHAUSTED"))
                .andExpect(jsonPath("$.delivery.attemptCount").value(5))
                .andExpect(jsonPath("$.delivery.nextAttemptAt").doesNotExist())
                .andExpect(jsonPath("$.delivery.completedAt").exists());
    }

    @Test
    void anEventWhoseDispatchHasNotHappenedYetIsStillReadable() throws Exception {
        mockMvc.perform(postEvent("evt_123"));

        // Before the dispatcher has run. The delivery is genuinely absent rather than unknown,
        // and there is no error because nothing went wrong.
        mockMvc.perform(get("/api/v1/events/{eventId}", "evt_123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value("evt_123"))
                .andExpect(jsonPath("$.dispatch.status").value("PENDING"))
                .andExpect(jsonPath("$.delivery").doesNotExist())
                .andExpect(jsonPath("$.deliveryLookupError").doesNotExist());
    }

    @Test
    void aStuckDispatchSaysSoRatherThanLookingEmpty() throws Exception {
        StubDeliveryService.rejecting(400);
        mockMvc.perform(postEvent("evt_123"));
        dispatcher.runOnce();

        // The delivery is null and always will be. Without the dispatch block this response
        // would be indistinguishable from an event that simply has not been dispatched yet.
        mockMvc.perform(get("/api/v1/events/{eventId}", "evt_123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dispatch.status").value("FAILED"))
                .andExpect(jsonPath("$.dispatch.attempts").value(1))
                .andExpect(jsonPath("$.dispatch.lastError").value(org.hamcrest.Matchers.containsString("400")))
                .andExpect(jsonPath("$.delivery").doesNotExist());
    }

    @Test
    void resubmissionsAreVisibleOnTheEvent() throws Exception {
        mockMvc.perform(postEvent("evt_123"));
        mockMvc.perform(postEvent("evt_123"));
        mockMvc.perform(postEvent("evt_123"));

        // The AC4 evidence, readable from the same place as everything else.
        mockMvc.perform(get("/api/v1/events/{eventId}", "evt_123"))
                .andExpect(jsonPath("$.duplicateSubmissionCount").value(2))
                .andExpect(jsonPath("$.lastDuplicateAt").exists());
    }

    @Test
    void anUnknownEventIsANotFoundProblem() throws Exception {
        mockMvc.perform(get("/api/v1/events/{eventId}", "evt_never_seen"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:caygnus:webhook:problem:event-not-found"))
                .andExpect(jsonPath("$.eventId").value("evt_never_seen"));
    }

    @Test
    void theDeliveryServiceIsNotAskedAboutAnEventItCannotKnowAbout() throws Exception {
        mockMvc.perform(postEvent("evt_123"));

        mockMvc.perform(get("/api/v1/events/{eventId}", "evt_123")).andExpect(status().isOk());

        // No dispatch means no delivery, so calling to be told so would spend a round trip and a
        // timeout budget to learn nothing.
        org.assertj.core.api.Assertions.assertThat(StubDeliveryService.totalReceived()).isZero();
    }

    private static DeliveryView deliveryWithThreeAttempts() {
        return new DeliveryView(
                DELIVERY_ID, "evt_123", "incident.created", DeliveryStatus.SUCCEEDED,
                "http://receiver:8082/receive", 3, 5,
                null, null, AttemptOutcome.SUCCESS,
                START, START, START.plusSeconds(30),
                List.of(
                        new AttemptView(1, START, START.plusMillis(1_034), 1_034,
                                AttemptOutcome.RETRYABLE_FAILURE, 503, null, null,
                                "service unavailable", null, START.plusSeconds(5), "delivery-1:7:a3f"),
                        new AttemptView(2, START.plusSeconds(5), START.plusSeconds(10), 5_000,
                                AttemptOutcome.RETRYABLE_FAILURE, null, ErrorClass.READ_TIMEOUT,
                                "Read timed out", null, null, START.plusSeconds(30), "delivery-1:7:a3f"),
                        new AttemptView(3, START.plusSeconds(30), START.plusSeconds(30).plusMillis(87), 87,
                                AttemptOutcome.SUCCESS, 200, null, null, "ok", null, null,
                                "delivery-1:7:a3f")));
    }

    private static DeliveryView exhaustedDelivery() {
        return new DeliveryView(
                DELIVERY_ID, "evt_dead", "incident.created", DeliveryStatus.FAILED_EXHAUSTED,
                "http://receiver:8082/receive", 5, 5,
                null, TerminalReason.ATTEMPTS_EXHAUSTED, AttemptOutcome.RETRYABLE_FAILURE,
                START, START, START.plusSeconds(755),
                List.of());
    }
}
