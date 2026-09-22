package com.caygnus.webhook.ingest.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;

/**
 * What the caller gets back from {@code POST /api/v1/events}.
 *
 * <p>{@code duplicate} is information, not control flow. A caller that ignores it and simply
 * retries on any failure is still correct, which is the property that makes this endpoint safe to
 * put behind an at-least-once producer.
 *
 * @param deliveryId null until the dispatcher has handed the event on; the event is accepted and
 *                   durable either way
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IngestEventResponse(
        String eventId,
        UUID deliveryId,
        String status,
        boolean duplicate,
        Integer duplicateSubmissionCount) {

    public static IngestEventResponse accepted(String eventId) {
        return new IngestEventResponse(eventId, null, "ACCEPTED", false, null);
    }

    public static IngestEventResponse duplicate(String eventId, UUID deliveryId, int submissions) {
        return new IngestEventResponse(eventId, deliveryId, "ACCEPTED", true, submissions);
    }
}
