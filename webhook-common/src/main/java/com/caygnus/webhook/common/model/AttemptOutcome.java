package com.caygnus.webhook.common.model;

/**
 * The classification of a single delivery attempt, as decided by {@code ResponseClassifier}.
 *
 * <p>This is the only input the state machine uses to pick the next {@link DeliveryStatus}, which
 * is why the classification policy lives in one pure function rather than being spread across
 * exception handling.
 */
public enum AttemptOutcome {

    /** HTTP 2xx. */
    SUCCESS,

    /** A failure a later attempt could plausibly succeed at: 408, 425, 429, 5xx (bar 501/505), or a transport error. */
    RETRYABLE_FAILURE,

    /** A failure that will not change on retry: any other 4xx, 1xx, 3xx, 501, 505, TLS or URL errors. */
    PERMANENT_FAILURE
}
