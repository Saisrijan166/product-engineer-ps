package com.caygnus.webhook.ingest.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * The event contract from the problem statement.
 *
 * <p>{@code eventId} is caller-supplied and is the whole basis of idempotent ingestion: submitting
 * this request twice must produce one event and one delivery.
 *
 * <p>Size and presence are checked here; the two rules that need context -- the payload's
 * serialised size and whether {@code occurredAt} is plausible against our clock -- are checked in
 * the use case, which has both.
 */
public record IngestEventRequest(
        @NotBlank @Size(max = 128) String eventId,
        @NotBlank @Size(max = 128) String type,
        OffsetDateTime occurredAt,
        @NotNull Map<String, Object> payload) {
}
