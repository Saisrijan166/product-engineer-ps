package com.caygnus.webhook.common.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * The ingest service asking the delivery service to schedule a delivery.
 *
 * <p>{@code eventId} is the idempotency key. Sending this request twice -- which the outbox
 * dispatcher will do if it crashes between sending and recording that it sent -- produces one
 * delivery job, because the delivery table's unique constraint says so.
 *
 * @param payload the event body, forwarded to the receiver. Typed as a map so it travels as a JSON
 *                object rather than a quoted string; the delivery service serialises it once when
 *                storing it, so a receiver gets equivalent JSON but not byte-identical whitespace.
 */
public record CreateDeliveryRequest(
        @NotBlank @Size(max = 128) String eventId,
        @Size(max = 128) String eventType,
        OffsetDateTime occurredAt,
        @NotNull Map<String, Object> payload) {
}
