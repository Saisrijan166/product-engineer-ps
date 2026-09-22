package com.caygnus.webhook.delivery.api;

import com.caygnus.webhook.common.api.AttemptView;
import com.caygnus.webhook.common.api.DeliveryView;
import com.caygnus.webhook.delivery.domain.Delivery;
import com.caygnus.webhook.delivery.domain.DeliveryAttempt;
import java.util.List;

/**
 * Entities to wire contracts.
 *
 * <p>It exists so the shared contract module never has to know what a JPA entity looks like: the
 * two services agree on {@link DeliveryView}, and only this service knows it is backed by a row
 * with a lease and a version on it. The lease owner and version are deliberately not exposed --
 * they are how this service does its job, not part of what it promises.
 */
public final class DeliveryViewMapper {

    private DeliveryViewMapper() {
    }

    /** @param attempts null to leave the history out, which is what listings do */
    public static DeliveryView toView(Delivery delivery, List<DeliveryAttempt> attempts) {
        return new DeliveryView(
                delivery.getId(),
                delivery.getEventId(),
                delivery.getEventType(),
                delivery.getStatus(),
                delivery.getTargetUrl(),
                delivery.getAttemptCount(),
                delivery.getMaxAttempts(),
                delivery.getNextAttemptAt(),
                delivery.getTerminalReason(),
                delivery.getLastOutcome(),
                delivery.getCreatedAt(),
                delivery.getFirstAttemptAt(),
                delivery.getCompletedAt(),
                attempts == null ? null : toViews(attempts));
    }

    public static List<AttemptView> toViews(List<DeliveryAttempt> attempts) {
        return attempts.stream().map(DeliveryViewMapper::toView).toList();
    }

    public static AttemptView toView(DeliveryAttempt attempt) {
        return new AttemptView(
                attempt.getAttemptNumber(),
                attempt.getStartedAt(),
                attempt.getCompletedAt(),
                attempt.getDurationMs(),
                attempt.getOutcome(),
                attempt.getHttpStatus(),
                attempt.getErrorClass(),
                attempt.getErrorMessage(),
                attempt.getResponseBodySnippet(),
                attempt.getRetryAfterSeconds(),
                attempt.getNextAttemptAt(),
                attempt.getWorkerId());
    }
}
