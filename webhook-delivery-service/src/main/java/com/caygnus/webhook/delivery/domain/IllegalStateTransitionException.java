package com.caygnus.webhook.delivery.domain;

import com.caygnus.webhook.common.model.DeliveryStatus;

/**
 * A delivery was asked to move between states the machine does not connect.
 *
 * <p>This is always a bug rather than a condition to handle: the claim query cannot return a
 * terminal delivery, and the reaper cannot see one that is not in flight. It is unchecked so that
 * the transaction rolls back and the delivery is left exactly as it was.
 */
public class IllegalStateTransitionException extends RuntimeException {

    private final transient DeliveryStatus from;
    private final transient DeliveryStatus to;

    public IllegalStateTransitionException(DeliveryStatus from, DeliveryStatus to) {
        super("Illegal delivery transition %s -> %s; legal targets are %s"
                .formatted(from, to, DeliveryStateMachine.legalTargets(from)));
        this.from = from;
        this.to = to;
    }

    public DeliveryStatus from() {
        return from;
    }

    public DeliveryStatus to() {
        return to;
    }
}
