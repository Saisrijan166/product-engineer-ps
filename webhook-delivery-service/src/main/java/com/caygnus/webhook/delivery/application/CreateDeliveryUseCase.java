package com.caygnus.webhook.delivery.application;

import com.caygnus.webhook.common.api.CreateDeliveryRequest;
import com.caygnus.webhook.delivery.config.DeliveryProperties;
import com.caygnus.webhook.delivery.config.RetryProperties;
import com.caygnus.webhook.delivery.domain.Delivery;
import com.caygnus.webhook.delivery.infrastructure.persistence.DeliveryRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates a delivery, or reports the one that already exists.
 *
 * <p>The idempotency lives entirely in {@code INSERT ... ON CONFLICT (event_id) DO NOTHING}. There
 * is no lock, no read-then-write, and no caught constraint violation: two dispatches of the same
 * event racing on two instances produce one delivery and one winner, decided by PostgreSQL. The
 * loser is not an error, it is just a row count of zero.
 *
 * <p>That matters because the outbox dispatcher is at-least-once by construction -- it can send,
 * crash, and send again -- so this endpoint being genuinely idempotent is what stops a duplicated
 * dispatch from becoming a duplicated delivery job.
 */
@Service
public class CreateDeliveryUseCase {

    private static final Logger log = LoggerFactory.getLogger(CreateDeliveryUseCase.class);

    private final DeliveryRepository deliveries;
    private final DeliveryProperties deliveryProperties;
    private final RetryProperties retryProperties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    CreateDeliveryUseCase(
            DeliveryRepository deliveries,
            DeliveryProperties deliveryProperties,
            RetryProperties retryProperties,
            ObjectMapper objectMapper,
            Clock clock) {
        this.deliveries = deliveries;
        this.deliveryProperties = deliveryProperties;
        this.retryProperties = retryProperties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * @return the delivery for this event, and whether this call is what created it
     */
    @Transactional
    public Result create(CreateDeliveryRequest request) {
        Delivery candidate = Delivery.create(
                request.eventId(),
                request.eventType(),
                request.occurredAt(),
                serialise(request.payload()),
                deliveryProperties.targetUrl(),
                retryProperties.maxAttempts(),
                clock.instant());

        int rowsInserted = deliveries.insertIfAbsent(
                candidate.getId(),
                candidate.getEventId(),
                candidate.getEventType(),
                candidate.getOccurredAt(),
                candidate.getPayload(),
                candidate.getTargetUrl(),
                candidate.getMaxAttempts(),
                candidate.getNextAttemptAt(),
                candidate.getCreatedAt());

        if (rowsInserted == 1) {
            // The id was generated before the insert, so winning means we already know it and need
            // no round trip to find out.
            log.info("Created delivery deliveryId={} eventId={} maxAttempts={}",
                    candidate.getId(), candidate.getEventId(), candidate.getMaxAttempts());
            return new Result(candidate.getId(), true);
        }

        UUID existingId = deliveries.findByEventId(request.eventId())
                .orElseThrow(() -> new ConcurrentCreationException(request.eventId()))
                .getId();
        log.debug("Delivery already existed for eventId={}, reporting deliveryId={}",
                request.eventId(), existingId);
        return new Result(existingId, false);
    }

    private String serialise(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // Jackson has already parsed this map out of the request body, so it cannot fail to
            // write it back. Unchecked rather than swallowed, because if it ever does, storing a
            // half-formed payload would be much worse than a 500.
            throw new IllegalStateException("Could not serialise an already-parsed payload", e);
        }
    }

    /**
     * The insert conflicted with a row that is no longer there.
     *
     * <p>Rare but real: {@code ON CONFLICT DO NOTHING} waits for the conflicting transaction, and
     * if that one rolls back, this insert can report nothing inserted with no row to find. Surfaced
     * as a 500 on purpose -- the dispatcher retries 5xx, and retrying is exactly right, because a
     * repeat of this request is harmless.
     */
    public static class ConcurrentCreationException extends IllegalStateException {
        public ConcurrentCreationException(String eventId) {
            super("Insert for eventId=%s conflicted with a row that then disappeared; safe to retry"
                    .formatted(eventId));
        }
    }

    /** @param created true when this call inserted the delivery, false when one already existed */
    public record Result(UUID deliveryId, boolean created) {
    }
}
