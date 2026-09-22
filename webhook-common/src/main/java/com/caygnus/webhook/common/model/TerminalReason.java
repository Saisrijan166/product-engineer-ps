package com.caygnus.webhook.common.model;

/**
 * Why a delivery stopped. Set only on a terminal failure; {@code null} while a delivery is live
 * and on success, where {@link DeliveryStatus#SUCCEEDED} is explanation enough.
 */
public enum TerminalReason {

    /** The receiver answered with a response classified as {@link AttemptOutcome#PERMANENT_FAILURE}. */
    NON_RETRYABLE_RESPONSE,

    /** The attempt budget was spent; the last attempt was a retryable failure. */
    ATTEMPTS_EXHAUSTED
}
