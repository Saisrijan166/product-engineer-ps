package com.caygnus.webhook.ingest.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.caygnus.webhook.ingest.support.AbstractIngestTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * The same identifier, a different event.
 *
 * <p>Not one of the acceptance criteria, and the brief would be satisfied by treating this as an
 * ordinary duplicate. It is rejected instead because a caller-supplied identifier is a promise
 * that the event is the same event, and there is no good answer once that promise is broken:
 * ignoring the new payload loses data the caller believes it sent, and replacing the old one
 * changes an event that may already be on its way to the receiver. Refusing is the only option
 * that does not silently pick one.
 */
class PayloadMismatchTest extends AbstractIngestTest {

    @Test
    void reusingAnIdentifierWithADifferentPayloadIsRejected() throws Exception {
        mockMvc.perform(postEvent("evt_123", """
                {"incidentId": "inc_456", "severity": "high"}""")).andExpect(status().isCreated());

        mockMvc.perform(postEvent("evt_123", """
                {"incidentId": "inc_456", "severity": "low"}"""))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:caygnus:webhook:problem:payload-mismatch"))
                .andExpect(jsonPath("$.eventId").value("evt_123"));
    }

    @Test
    void theOriginalEventIsLeftExactlyAsItWas() throws Exception {
        mockMvc.perform(postEvent("evt_123", """
                {"severity": "high"}"""));

        mockMvc.perform(postEvent("evt_123", """
                {"severity": "low"}""")).andExpect(status().isConflict());

        // Not replaced, and not counted as a resubmission either -- it was not one.
        assertThat(events.count()).isEqualTo(1);
        assertThat(events.findByEventId("evt_123").orElseThrow().getPayload()).contains("high");
        assertThat(duplicateSubmissionCountOf("evt_123")).isZero();
        assertThat(outbox.count()).isEqualTo(1);
    }

    @Test
    void anAddedFieldCountsAsADifferentPayload() throws Exception {
        mockMvc.perform(postEvent("evt_123", """
                {"incidentId": "inc_456"}"""));

        mockMvc.perform(postEvent("evt_123", """
                {"incidentId": "inc_456", "severity": "high"}""")).andExpect(status().isConflict());
    }

    @Test
    void aRejectedMismatchDoesNotStopTheRealEventBeingResubmitted() throws Exception {
        mockMvc.perform(postEvent("evt_123", """
                {"severity": "high"}"""));
        mockMvc.perform(postEvent("evt_123", """
                {"severity": "low"}""")).andExpect(status().isConflict());

        // The conflict rolled back cleanly; the event is still there and still idempotent.
        mockMvc.perform(postEvent("evt_123", """
                {"severity": "high"}""")).andExpect(status().isOk());
        assertThat(duplicateSubmissionCountOf("evt_123")).isEqualTo(1);
    }

    @Test
    void aMissingEventIdIsRejectedBeforeAnythingIsStored() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"incident.created","payload":{"severity":"high"}}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:caygnus:webhook:problem:validation-failed"))
                .andExpect(jsonPath("$.errors.eventId").exists());

        assertThat(events.count()).isZero();
    }

    @Test
    void anImplausibleOccurredAtIsRejected() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId":"evt_future","type":"incident.created",
                                 "occurredAt":"2030-01-01T00:00:00Z","payload":{"a":1}}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.occurredAt").exists());

        assertThat(events.count()).isZero();
    }

    @Test
    void anOversizedPayloadIsRejectedBeforeItIsStored() throws Exception {
        String huge = "\"" + "x".repeat(300_000) + "\"";

        mockMvc.perform(postEvent("evt_huge", "{\"blob\": " + huge + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.payload").exists());

        assertThat(events.count()).isZero();
    }
}
