package com.caygnus.webhook.ingest.api.dto;

import com.caygnus.webhook.ingest.domain.WebhookEvent;
import java.time.Instant;
import java.time.OffsetDateTime;

/** One row of the event listing. Deliberately without the payload, which can be 256 KB. */
public record EventSummaryView(
        String eventId,
        String type,
        OffsetDateTime occurredAt,
        Instant receivedAt,
        int duplicateSubmissionCount,
        Instant lastDuplicateAt) {

    public static EventSummaryView of(WebhookEvent event) {
        return new EventSummaryView(
                event.getEventId(),
                event.getType(),
                event.getOccurredAt(),
                event.getReceivedAt(),
                event.getDuplicateSubmissionCount(),
                event.getLastDuplicateAt());
    }
}
