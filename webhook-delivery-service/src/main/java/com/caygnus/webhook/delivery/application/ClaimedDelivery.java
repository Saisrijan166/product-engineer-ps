package com.caygnus.webhook.delivery.application;

import com.caygnus.webhook.delivery.domain.Delivery;
import java.util.UUID;

/**
 * A snapshot of everything an attempt needs, taken while the claim transaction is still open.
 *
 * <p>Deliberately not the entity. The attempt runs after that transaction has committed, so
 * passing the entity across would hand a worker a detached object whose lazy collection blows up
 * the moment anything touches it -- and would invite reading state that may already have moved on.
 * A record makes it obvious that the worker is acting on what was true at claim time.
 *
 * @param attemptNumber the attempt this claim bought, already counted against the budget
 */
public record ClaimedDelivery(
        UUID deliveryId,
        String eventId,
        String eventType,
        String payload,
        String targetUrl,
        int attemptNumber,
        int maxAttempts) {

    static ClaimedDelivery of(Delivery delivery) {
        return new ClaimedDelivery(
                delivery.getId(),
                delivery.getEventId(),
                delivery.getEventType(),
                delivery.getPayload(),
                delivery.getTargetUrl(),
                delivery.getAttemptCount(),
                delivery.getMaxAttempts());
    }
}
