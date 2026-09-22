package com.caygnus.webhook.ingest.application;

import com.caygnus.webhook.common.api.CreateDeliveryRequest;
import com.caygnus.webhook.ingest.api.dto.IngestEventRequest;
import com.caygnus.webhook.ingest.config.IngestProperties;
import com.caygnus.webhook.ingest.domain.InvalidEventException;
import com.caygnus.webhook.ingest.domain.OutboxDispatch;
import com.caygnus.webhook.ingest.domain.PayloadHasher;
import com.caygnus.webhook.ingest.domain.PayloadMismatchException;
import com.caygnus.webhook.ingest.domain.WebhookEvent;
import com.caygnus.webhook.ingest.infrastructure.observability.IngestMetrics;
import com.caygnus.webhook.ingest.infrastructure.persistence.OutboxDispatchRepository;
import com.caygnus.webhook.ingest.infrastructure.persistence.WebhookEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Accepting an event, once, no matter how many times it is submitted.
 *
 * <p><b>There is no lock in this class, and that is the design rather than an omission.</b> An
 * application-level lock would need somewhere to live -- a row, a table, another service -- and
 * every one of those answers introduces a thing that can be held by a process that has died. The
 * database already serialises writes to a unique index, so the question "has this event been seen
 * before" is answered by the same statement that accepts it, atomically, once.
 *
 * <p>The event and the intent to deliver it commit together or not at all. That is what makes the
 * handoff lossless: there is no instant at which a caller has been told 201 while the decision to
 * deliver exists only in this process's memory.
 */
@Service
public class IngestEventUseCase {

    /**
     * Clock skew between a caller and this service is normal; a timestamp a day out is not. Far
     * enough ahead to catch a wrong year or a millisecond/second mix-up without rejecting anyone
     * whose NTP is merely lagging.
     */
    private static final Duration MAX_CLOCK_SKEW = Duration.ofHours(24);

    private static final Logger log = LoggerFactory.getLogger(IngestEventUseCase.class);

    private final WebhookEventRepository events;
    private final OutboxDispatchRepository outbox;
    private final ObjectMapper objectMapper;
    private final IngestMetrics metrics;
    private final int maxPayloadBytes;
    private final Clock clock;

    IngestEventUseCase(
            WebhookEventRepository events,
            OutboxDispatchRepository outbox,
            ObjectMapper objectMapper,
            IngestMetrics metrics,
            IngestProperties properties,
            Clock clock) {
        this.events = events;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.maxPayloadBytes = properties.maxPayloadBytes();
        this.clock = clock;
    }

    /**
     * Accept an event, or report the one already accepted under this identifier.
     *
     * @throws PayloadMismatchException if the identifier is known but the payload is not the same
     * @throws InvalidEventException    if the request is well-formed but not plausible
     */
    @Transactional
    public Result ingest(IngestEventRequest request) {
        String payloadJson = serialise(request.payload());
        validate(request, payloadJson);

        String payloadHash = PayloadHasher.sha256(request.payload());
        Instant now = clock.instant();

        int accepted = events.insertIfAbsent(
                UUID.randomUUID(),
                request.eventId(),
                request.type(),
                request.occurredAt(),
                payloadJson,
                payloadHash,
                now);

        if (accepted == 1) {
            // Same transaction. The outbox row is not a follow-up action that might not happen;
            // it is part of what "accepted" means.
            outbox.insertIfAbsent(request.eventId(), dispatchPayload(request), now);
            log.info("Accepted eventId={} type={}", request.eventId(), request.type());
            return Result.accepted(request.eventId());
        }

        return recordResubmission(request, payloadHash, now);
    }

    /**
     * A second sighting of an event we already hold.
     *
     * <p>The comparison and the increment are one statement, so there is no window between
     * deciding the payload matches and counting the submission. A zero row count means it did not
     * match -- or, vanishingly rarely, that the conflicting transaction rolled back and left
     * nothing behind, which the lookup below tells apart.
     */
    private Result recordResubmission(IngestEventRequest request, String payloadHash, Instant now) {
        int counted = events.recordDuplicateSubmission(request.eventId(), payloadHash, now);
        Optional<WebhookEvent> existing = events.findByEventId(request.eventId());

        if (existing.isEmpty()) {
            throw new ConcurrentIngestException(request.eventId());
        }
        if (counted == 0) {
            throw new PayloadMismatchException(request.eventId());
        }

        WebhookEvent event = existing.get();
        UUID deliveryId = outbox.findByEventId(request.eventId())
                .map(OutboxDispatch::getDeliveryId)
                .orElse(null);

        metrics.duplicateSubmission();
        log.debug("eventId={} resubmitted, now seen {} extra times",
                request.eventId(), event.getDuplicateSubmissionCount());
        return Result.duplicate(event.getEventId(), deliveryId, event.getDuplicateSubmissionCount());
    }

    private void validate(IngestEventRequest request, String payloadJson) {
        int payloadBytes = payloadJson.getBytes(StandardCharsets.UTF_8).length;
        if (payloadBytes > maxPayloadBytes) {
            throw new InvalidEventException(
                    "payload", "is %d bytes, above the %d byte limit".formatted(payloadBytes, maxPayloadBytes));
        }
        // Checked here rather than by an annotation because it needs the injected clock, and
        // nothing in this service is allowed to read the wall clock directly.
        OffsetDateTime occurredAt = request.occurredAt();
        if (occurredAt != null && occurredAt.toInstant().isAfter(clock.instant().plus(MAX_CLOCK_SKEW))) {
            throw new InvalidEventException("occurredAt", "is more than 24 hours in the future");
        }
    }

    /** Everything the delivery service will need, serialised now so the dispatcher needs no context. */
    private String dispatchPayload(IngestEventRequest request) {
        return serialise(new CreateDeliveryRequest(
                request.eventId(), request.type(), request.occurredAt(), request.payload()));
    }

    private String serialise(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise an already-parsed request", e);
        }
    }

    /**
     * The insert conflicted with a row that is no longer there.
     *
     * <p>{@code ON CONFLICT DO NOTHING} waits for the conflicting transaction, and if that one
     * rolls back this insert can report nothing inserted with nothing to find. Surfaced as a 503
     * rather than a 500: nothing is broken and the caller should simply try again.
     */
    public static class ConcurrentIngestException extends IllegalStateException {
        public ConcurrentIngestException(String eventId) {
            super("Accepting eventId=%s raced with a transaction that rolled back; safe to retry"
                    .formatted(eventId));
        }
    }

    /**
     * @param accepted true when this call is what accepted the event
     * @param deliveryId null until the dispatcher has handed the event to the delivery service
     */
    public record Result(String eventId, UUID deliveryId, boolean accepted, int duplicateSubmissionCount) {

        static Result accepted(String eventId) {
            return new Result(eventId, null, true, 0);
        }

        static Result duplicate(String eventId, UUID deliveryId, int duplicateSubmissionCount) {
            return new Result(eventId, deliveryId, false, duplicateSubmissionCount);
        }
    }
}
