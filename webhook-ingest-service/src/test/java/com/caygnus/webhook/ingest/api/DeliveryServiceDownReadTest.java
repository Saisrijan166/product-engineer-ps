package com.caygnus.webhook.ingest.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.caygnus.webhook.ingest.support.AbstractIngestTest;
import com.caygnus.webhook.ingest.support.StubDeliveryService;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The composed read when half of it cannot be reached.
 *
 * <p>An easy thing to get wrong by doing the obvious thing. The read calls another service, that
 * call throws, and the exception propagates to a 500 -- so an outage in the delivery service
 * makes the ingest service look broken too, and the event data that is sitting right there,
 * perfectly intact, becomes unavailable at precisely the moment somebody is trying to work out
 * what is going on.
 *
 * <p>So the failure is reported rather than thrown. The caller gets a 200, everything this
 * service knows, and an explicit note saying what is missing and why.
 */
class DeliveryServiceDownReadTest extends AbstractIngestTest {

    @AfterEach
    void bringTheServiceBack() {
        StubDeliveryService.restart();
    }

    @Test
    void anUnreachableDeliveryServiceDoesNotFailTheRead() throws Exception {
        UUID acknowledged = dispatchedEvent("evt_123");

        StubDeliveryService.stop();

        mockMvc.perform(get("/api/v1/events/{eventId}", "evt_123"))
                // 200, not 500. The half of the answer we hold is still correct.
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value("evt_123"))
                .andExpect(jsonPath("$.type").value("incident.created"))
                .andExpect(jsonPath("$.receivedAt").exists())
                .andExpect(jsonPath("$.dispatch.status").value("DISPATCHED"))
                // Absent rather than wrong...
                .andExpect(jsonPath("$.delivery").doesNotExist())
                // ...and said so, rather than looking like an event with no delivery.
                .andExpect(jsonPath("$.deliveryLookupError").isNotEmpty());

        assertThat(acknowledged).isNotNull();
    }

    @Test
    void aDeliveryServiceReturningErrorsDegradesTheSameWay() throws Exception {
        dispatchedEvent("evt_123");
        StubDeliveryService.failing(500);

        mockMvc.perform(get("/api/v1/events/{eventId}", "evt_123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivery").doesNotExist())
                .andExpect(jsonPath("$.deliveryLookupError").value(
                        org.hamcrest.Matchers.containsString("500")));
    }

    @Test
    void theDegradedReadIsDistinguishableFromAnEventWithNoDeliveryYet() throws Exception {
        // Not yet dispatched: no delivery, and nothing went wrong.
        mockMvc.perform(postEvent("evt_pending"));
        mockMvc.perform(get("/api/v1/events/{eventId}", "evt_pending"))
                .andExpect(jsonPath("$.delivery").doesNotExist())
                .andExpect(jsonPath("$.deliveryLookupError").doesNotExist());

        // Dispatched but unreachable: no delivery, and something did.
        dispatchedEvent("evt_dispatched");
        StubDeliveryService.stop();
        mockMvc.perform(get("/api/v1/events/{eventId}", "evt_dispatched"))
                .andExpect(jsonPath("$.delivery").doesNotExist())
                .andExpect(jsonPath("$.deliveryLookupError").isNotEmpty());

        // Both leave `delivery` null and they mean opposite things. A reader that could not tell
        // them apart would have to guess whether the engine had lost the event.
    }

    @Test
    void theListingIsUnaffectedBecauseItNeverAsksTheDeliveryService() throws Exception {
        dispatchedEvent("evt_1");
        dispatchedEvent("evt_2");
        StubDeliveryService.stop();

        mockMvc.perform(get("/api/v1/events").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEvents").value(2));
    }

    @Test
    void ingestionKeepsWorkingWhileTheDeliveryServiceIsDown() throws Exception {
        StubDeliveryService.stop();

        // The whole reason the outbox exists: accepting an event does not depend on anything
        // downstream being up.
        mockMvc.perform(postEvent("evt_123")).andExpect(status().isCreated());
        mockMvc.perform(get("/api/v1/events/{eventId}", "evt_123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dispatch.status").value("PENDING"));
    }

    @Test
    void theReadRecoversOnceTheServiceIsBack() throws Exception {
        dispatchedEvent("evt_123");
        StubDeliveryService.stop();
        mockMvc.perform(get("/api/v1/events/{eventId}", "evt_123"))
                .andExpect(jsonPath("$.deliveryLookupError").isNotEmpty());

        StubDeliveryService.restart();

        // Nothing to reset or re-drive: the degradation was per-request, with no state behind it.
        mockMvc.perform(get("/api/v1/events/{eventId}", "evt_123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryLookupError").doesNotExist());
    }

    /** An event that has been accepted and handed over, so a lookup would normally follow. */
    private UUID dispatchedEvent(String eventId) throws Exception {
        UUID deliveryId = UUID.randomUUID();
        StubDeliveryService.acceptingAs(deliveryId);
        mockMvc.perform(postEvent(eventId));
        dispatcher.runOnce();
        return deliveryId;
    }
}
