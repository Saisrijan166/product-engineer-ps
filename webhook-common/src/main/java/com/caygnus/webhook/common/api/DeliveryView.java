package com.caygnus.webhook.common.api;

import com.caygnus.webhook.common.model.AttemptOutcome;
import com.caygnus.webhook.common.model.DeliveryStatus;
import com.caygnus.webhook.common.model.TerminalReason;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A delivery's current state, and optionally its whole history.
 *
 * @param deliveryId      also the {@code X-Idempotency-Key} the receiver sees, identical across
 *                        every attempt
 * @param targetUrl       as resolved when the delivery was created, not as configured now
 * @param nextAttemptAt   set only while waiting to be retried
 * @param terminalReason  set only on a terminal failure
 * @param attempts        null when the delivery was returned as part of a list; listing histories
 *                        alongside deliveries is the N+1 this API declines to offer by default
 */
public record DeliveryView(
        UUID deliveryId,
        String eventId,
        String eventType,
        DeliveryStatus status,
        String targetUrl,
        int attemptCount,
        int maxAttempts,
        Instant nextAttemptAt,
        TerminalReason terminalReason,
        AttemptOutcome lastOutcome,
        Instant createdAt,
        Instant firstAttemptAt,
        Instant completedAt,
        List<AttemptView> attempts) {
}
