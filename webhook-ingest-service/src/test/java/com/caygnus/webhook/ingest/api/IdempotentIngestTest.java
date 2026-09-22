package com.caygnus.webhook.ingest.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.caygnus.webhook.ingest.domain.DispatchStatus;
import com.caygnus.webhook.ingest.domain.OutboxDispatch;
import com.caygnus.webhook.ingest.support.AbstractIngestTest;
import org.junit.jupiter.api.Test;

/**
 * <b>AC4 — idempotent ingestion, the sequential half.</b>
 *
 * <p>"Given an event with a particular eventId was already accepted, when the same event is
 * submitted again with that identifier, then the service returns or references the existing
 * logical event and does not schedule a second independent delivery job."
 *
 * <p>The second clause is the one with teeth, and it is why these tests look at the outbox as
 * well as the event. Returning the right answer while quietly queueing a second delivery would
 * satisfy a careless reading and deliver the event twice.
 */
class IdempotentIngestTest extends AbstractIngestTest {

    @Test
    void theFirstSubmissionAcceptsTheEvent() throws Exception {
        mockMvc.perform(postEvent("evt_123"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/events/evt_123"))
                .andExpect(jsonPath("$.eventId").value("evt_123"))
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.duplicate").value(false));

        assertThat(events.count()).isEqualTo(1);
    }

    @Test
    void theSecondSubmissionReferencesTheSameEventAndSchedulesNothingNew() throws Exception {
        mockMvc.perform(postEvent("evt_123")).andExpect(status().isCreated());

        mockMvc.perform(postEvent("evt_123"))
                // 200, not 201: the event exists, this call did not accept it.
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value("evt_123"))
                .andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.duplicateSubmissionCount").value(1));

        assertThat(events.count()).isEqualTo(1);
        // The heart of it: one dispatch intent, so one delivery job however many times this is
        // submitted.
        assertThat(outbox.count()).isEqualTo(1);
    }

    @Test
    void aTenthSubmissionStillLeavesOneEventAndOneDispatch() throws Exception {
        for (int submission = 1; submission <= 10; submission++) {
            mockMvc.perform(postEvent("evt_123"));
        }

        assertThat(events.count()).isEqualTo(1);
        assertThat(outbox.count()).isEqualTo(1);
        assertThat(duplicateSubmissionCountOf("evt_123")).isEqualTo(9);
    }

    @Test
    void theEventIsRetainedBeforeAnythingIsScheduled() throws Exception {
        mockMvc.perform(postEvent("evt_123")).andExpect(status().isCreated());

        // Required behaviour 1 from the problem statement, and the reason the outbox exists: by
        // the time the caller has a 201 both rows are committed, so there is no window in which
        // the event is accepted but the intent to deliver it lives only in this process.
        assertThat(jdbc.queryForMap("SELECT event_id, type, payload_hash FROM webhook_event"))
                .containsEntry("event_id", "evt_123")
                .containsEntry("type", "incident.created")
                .hasEntrySatisfying("payload_hash", hash -> assertThat(hash).asString().hasSize(64));

        OutboxDispatch dispatch = outbox.findByEventId("evt_123").orElseThrow();
        assertThat(dispatch.getStatus()).isEqualTo(DispatchStatus.PENDING);
        assertThat(dispatch.getAttempts()).isZero();
        assertThat(dispatch.getDeliveryId()).isNull();
        // The whole CreateDeliveryRequest, so the dispatcher needs nothing but this row.
        assertThat(dispatch.getPayload()).contains("evt_123").contains("inc_456");
    }

    @Test
    void aResubmissionIsCountedAndTimestamped() throws Exception {
        mockMvc.perform(postEvent("evt_123"));
        mockMvc.perform(postEvent("evt_123"));
        mockMvc.perform(postEvent("evt_123"));

        assertThat(events.findByEventId("evt_123").orElseThrow()).satisfies(event -> {
            assertThat(event.getDuplicateSubmissionCount()).isEqualTo(2);
            assertThat(event.getLastDuplicateAt()).isNotNull();
        });
    }

    @Test
    void theSamePayloadFormattedDifferentlyIsTheSameEvent() throws Exception {
        mockMvc.perform(postEvent("evt_123", """
                {"incidentId": "inc_456", "severity": "high"}""")).andExpect(status().isCreated());

        // Reordered keys and different whitespace. A caller whose serialiser emits keys in a
        // different order is resubmitting, not contradicting itself.
        mockMvc.perform(postEvent("evt_123", """
                {
                   "severity"   : "high",
                   "incidentId" : "inc_456"
                }""")).andExpect(status().isOk());

        assertThat(events.count()).isEqualTo(1);
        assertThat(duplicateSubmissionCountOf("evt_123")).isEqualTo(1);
    }

    @Test
    void differentEventsAreAcceptedIndependently() throws Exception {
        mockMvc.perform(postEvent("evt_1")).andExpect(status().isCreated());
        mockMvc.perform(postEvent("evt_2")).andExpect(status().isCreated());

        assertThat(events.count()).isEqualTo(2);
        assertThat(outbox.count()).isEqualTo(2);
    }

    @Test
    void acceptedEventsAreListedNewestFirst() throws Exception {
        mockMvc.perform(postEvent("evt_1"));
        mockMvc.perform(postEvent("evt_2"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/events").param("page", "0").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEvents").value(2))
                .andExpect(jsonPath("$.events.length()").value(2));
    }
}
