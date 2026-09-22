package com.caygnus.webhook.delivery.domain;

import com.caygnus.webhook.common.model.AttemptOutcome;
import com.caygnus.webhook.common.model.ErrorClass;

/**
 * What one attempt produced, already classified.
 *
 * <p>There is no constructor that lets a caller state the outcome directly: every way of making
 * one of these runs {@link ResponseClassifier}. That is deliberate. The retry policy is only
 * trustworthy if it is impossible to bypass, and a call site that could decide for itself that
 * some 404 was "probably worth retrying" is exactly how a documented policy stops being true.
 *
 * @param httpStatus          set when the receiver answered, null when it did not
 * @param errorClass          the reverse: set only when no answer arrived
 * @param retryAfterSeconds   the receiver's {@code Retry-After}, if it sent a usable one
 */
public record DeliveryOutcome(
        AttemptOutcome outcome,
        Integer httpStatus,
        ErrorClass errorClass,
        String errorMessage,
        String responseBodySnippet,
        Integer retryAfterSeconds) {

    /** The receiver answered. Whether that is good news is the classifier's call, not ours. */
    public static DeliveryOutcome fromResponse(int httpStatus, String bodySnippet, Integer retryAfterSeconds) {
        return new DeliveryOutcome(
                ResponseClassifier.classify(httpStatus),
                httpStatus,
                null,
                null,
                bodySnippet,
                retryAfterSeconds);
    }

    /** No answer arrived: a timeout, a refused connection, a bad certificate. */
    public static DeliveryOutcome fromTransportFailure(ErrorClass errorClass, String message) {
        return new DeliveryOutcome(
                ResponseClassifier.classify(errorClass),
                null,
                errorClass,
                message,
                null,
                null);
    }

    public boolean isSuccess() {
        return outcome == AttemptOutcome.SUCCESS;
    }

    public boolean isRetryable() {
        return outcome == AttemptOutcome.RETRYABLE_FAILURE;
    }
}
