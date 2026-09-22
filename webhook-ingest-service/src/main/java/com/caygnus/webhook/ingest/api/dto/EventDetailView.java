package com.caygnus.webhook.ingest.api.dto;

import com.caygnus.webhook.common.api.DeliveryView;
import com.caygnus.webhook.ingest.domain.WebhookEvent;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * Everything known about one event: what was submitted, how the handoff went, and what the
 * delivery service made of it.
 *
 * <p>This is the answer to AC5 -- "the current state and ordered attempt history are available" --
 * and it deliberately spans both services so a reviewer needs one request rather than three and a
 * mental join.
 *
 * @param delivery            null when the dispatch has not happened yet, and null when the
 *                            delivery service could not be reached. {@code deliveryLookupError}
 *                            is what tells those two apart.
 * @param deliveryLookupError present only on a degraded read. Everything above it is still this
 *                            service's own data and is still correct.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventDetailView(
        String eventId,
        String type,
        OffsetDateTime occurredAt,
        Instant receivedAt,
        int duplicateSubmissionCount,
        Instant lastDuplicateAt,
        DispatchView dispatch,
        DeliveryView delivery,
        String deliveryLookupError) {

    public static EventDetailView of(
            WebhookEvent event, DispatchView dispatch, DeliveryView delivery, String deliveryLookupError) {

        return new EventDetailView(
                event.getEventId(),
                event.getType(),
                event.getOccurredAt(),
                event.getReceivedAt(),
                event.getDuplicateSubmissionCount(),
                event.getLastDuplicateAt(),
                dispatch,
                delivery,
                deliveryLookupError);
    }
}
