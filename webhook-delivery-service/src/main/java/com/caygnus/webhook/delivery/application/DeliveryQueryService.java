package com.caygnus.webhook.delivery.application;

import com.caygnus.webhook.common.api.DeliveryView;
import com.caygnus.webhook.common.model.DeliveryStatus;
import com.caygnus.webhook.delivery.api.DeliveryViewMapper;
import com.caygnus.webhook.delivery.domain.Delivery;
import com.caygnus.webhook.delivery.domain.DeliveryAttempt;
import com.caygnus.webhook.delivery.infrastructure.persistence.DeliveryAttemptRepository;
import com.caygnus.webhook.delivery.infrastructure.persistence.DeliveryRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads. This is the evidence side of the service: what state a delivery is in, and what happened
 * on the way there.
 *
 * <p>Attempts are loaded by an explicit query rather than through the entity's lazy collection, and
 * only when a caller asked for them. A list of fifty deliveries that quietly fetched fifty
 * histories is the N+1 the mapping was kept lazy to avoid.
 */
@Service
@Transactional(readOnly = true)
public class DeliveryQueryService {

    private final DeliveryRepository deliveries;
    private final DeliveryAttemptRepository attempts;

    DeliveryQueryService(DeliveryRepository deliveries, DeliveryAttemptRepository attempts) {
        this.deliveries = deliveries;
        this.attempts = attempts;
    }

    /** One delivery with its ordered history. */
    public Optional<DeliveryView> findById(UUID deliveryId) {
        return deliveries.findById(deliveryId).map(this::withAttempts);
    }

    /**
     * By event id. A list rather than a single result, so "no delivery yet" is an empty list rather
     * than a 404 -- the ingest service's composed read has to render that case, and it is a normal
     * state, not an error.
     */
    public List<DeliveryView> findByEventId(String eventId, boolean includeAttempts) {
        return deliveries.findByEventId(eventId)
                .map(delivery -> List.of(includeAttempts ? withAttempts(delivery) : withoutAttempts(delivery)))
                .orElseGet(List::of);
    }

    /** The dead-letter view when called with {@code FAILED_EXHAUSTED}. Histories omitted. */
    public List<DeliveryView> findByStatus(DeliveryStatus status, int limit) {
        return deliveries.findByStatusOrderByCreatedAtDesc(status, Pageable.ofSize(limit)).stream()
                .map(this::withoutAttempts)
                .toList();
    }

    public List<DeliveryView> findRecent(int limit) {
        return deliveries.findAllByOrderByCreatedAtDesc(Pageable.ofSize(limit)).stream()
                .map(this::withoutAttempts)
                .toList();
    }

    public List<DeliveryAttempt> attemptsOf(UUID deliveryId) {
        return attempts.findByDeliveryIdOrderByAttemptNumberAsc(deliveryId);
    }

    public boolean exists(UUID deliveryId) {
        return deliveries.existsById(deliveryId);
    }

    private DeliveryView withAttempts(Delivery delivery) {
        return DeliveryViewMapper.toView(
                delivery, attempts.findByDeliveryIdOrderByAttemptNumberAsc(delivery.getId()));
    }

    private DeliveryView withoutAttempts(Delivery delivery) {
        return DeliveryViewMapper.toView(delivery, null);
    }
}
