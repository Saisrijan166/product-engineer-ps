package com.caygnus.webhook.delivery.infrastructure.persistence;

import com.caygnus.webhook.delivery.domain.DeliveryAttempt;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The attempt history. Insert and read; nothing here updates a row, because an attempt is a record
 * of something that already happened.
 */
public interface DeliveryAttemptRepository extends JpaRepository<DeliveryAttempt, UUID> {

    /**
     * The ordered history behind AC5. Ordering is explicit rather than incidental: a reviewer
     * reading attempt 3 before attempt 1 would have to work out the sequence themselves.
     */
    List<DeliveryAttempt> findByDeliveryIdOrderByAttemptNumberAsc(UUID deliveryId);

    long countByDeliveryId(UUID deliveryId);
}
