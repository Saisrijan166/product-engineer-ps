package com.caygnus.webhook.common.api;

import com.caygnus.webhook.common.model.AttemptOutcome;
import com.caygnus.webhook.common.model.ErrorClass;
import java.time.Instant;

/**
 * One attempt, as a reviewer reads it.
 *
 * <p>Everything recorded is exposed. Between {@code httpStatus} and {@code errorClass} exactly one
 * is set: the receiver either answered or it did not, and which of those happened is usually the
 * first question anyone asks.
 */
public record AttemptView(
        int attemptNumber,
        Instant startedAt,
        Instant completedAt,
        Integer durationMs,
        AttemptOutcome outcome,
        Integer httpStatus,
        ErrorClass errorClass,
        String errorMessage,
        String responseBodySnippet,
        Integer retryAfterSeconds,
        Instant nextAttemptAt,
        String workerId) {
}
