package com.caygnus.webhook.common.model;

/**
 * Lifecycle of one logical delivery job.
 *
 * <p>Transitions are defined and enforced in the delivery service's {@code DeliveryStateMachine};
 * this enum only names the states so that both services can speak about them over the wire.
 *
 * <p>{@code SUCCEEDED}, {@code FAILED_PERMANENT} and {@code FAILED_EXHAUSTED} are terminal. The
 * scheduler's claim query selects only {@code PENDING} and {@code RETRY_SCHEDULED}, so a terminal
 * delivery is structurally invisible to it and can never be attempted again.
 */
public enum DeliveryStatus {

    /** Created and due immediately; never attempted. */
    PENDING,

    /** Claimed by a worker, lease held, HTTP attempt in progress or interrupted by a crash. */
    IN_FLIGHT,

    /** A retryable failure was recorded; waiting for {@code next_attempt_at} to pass. */
    RETRY_SCHEDULED,

    /** The receiver returned 2xx. Terminal. */
    SUCCEEDED,

    /** The receiver returned a non-retryable response. Terminal, without exhausting the budget. */
    FAILED_PERMANENT,

    /** The attempt budget was spent on retryable failures. Terminal. */
    FAILED_EXHAUSTED
}
