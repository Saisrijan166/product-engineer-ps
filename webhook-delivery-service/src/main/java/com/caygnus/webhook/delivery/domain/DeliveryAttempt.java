package com.caygnus.webhook.delivery.domain;

import com.caygnus.webhook.common.model.AttemptOutcome;
import com.caygnus.webhook.common.model.ErrorClass;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * What happened on one attempt. Written once, never changed.
 *
 * <p>This is the evidence the whole exercise is judged on, so it records enough to answer "why did
 * this delivery end up here" without anyone reading the logs: which attempt, when, how long it
 * took, what the receiver said, and what that scheduled next. Every field is set at construction
 * and there are no setters, so an attempt cannot be edited after the fact.
 *
 * <p>Two fields are truncated on the way in. A receiver is free to answer a failed webhook with a
 * megabyte of HTML, and storing that per attempt, for every delivery, forever, is how an audit
 * trail turns into an outage.
 */
@Entity
@Table(name = "delivery_attempt")
public class DeliveryAttempt {

    /** Enough of a response body to diagnose from, and no more. */
    static final int MAX_BODY_SNIPPET_LENGTH = 1024;

    static final int MAX_ERROR_MESSAGE_LENGTH = 512;

    @Id
    private UUID id;

    @Column(name = "delivery_id", nullable = false, updatable = false)
    private UUID deliveryId;

    @Column(name = "attempt_number", nullable = false, updatable = false)
    private int attemptNumber;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "completed_at", updatable = false)
    private Instant completedAt;

    @Column(name = "duration_ms", updatable = false)
    private Integer durationMs;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, updatable = false, length = 24)
    private AttemptOutcome outcome;

    @Column(name = "http_status", updatable = false)
    private Integer httpStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "error_class", updatable = false, length = 32)
    private ErrorClass errorClass;

    @Column(name = "error_message", updatable = false)
    private String errorMessage;

    @Column(name = "response_body_snippet", updatable = false)
    private String responseBodySnippet;

    @Column(name = "retry_after_seconds", updatable = false)
    private Integer retryAfterSeconds;

    @Column(name = "next_attempt_at", updatable = false)
    private Instant nextAttemptAt;

    @Column(name = "worker_id", nullable = false, updatable = false, length = 64)
    private String workerId;

    protected DeliveryAttempt() {
        // For Hibernate and for the static factory below.
    }

    /**
     * Record a finished attempt.
     *
     * @param httpStatus          null when the receiver never answered
     * @param errorClass          null when it did
     * @param responseBodySnippet truncated here rather than by the caller, so no call site can
     *                            forget
     * @param nextAttemptAt       what this attempt scheduled, or null if it ended the delivery
     */
    public static DeliveryAttempt record(
            UUID deliveryId,
            int attemptNumber,
            Instant startedAt,
            Instant completedAt,
            AttemptOutcome outcome,
            Integer httpStatus,
            ErrorClass errorClass,
            String errorMessage,
            String responseBodySnippet,
            Integer retryAfterSeconds,
            Instant nextAttemptAt,
            String workerId) {

        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber is 1-based, got " + attemptNumber);
        }
        DeliveryAttempt attempt = new DeliveryAttempt();
        attempt.id = UUID.randomUUID();
        attempt.deliveryId = Objects.requireNonNull(deliveryId, "deliveryId");
        attempt.attemptNumber = attemptNumber;
        attempt.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        attempt.completedAt = completedAt;
        attempt.durationMs = durationMillis(startedAt, completedAt);
        attempt.outcome = Objects.requireNonNull(outcome, "outcome");
        attempt.httpStatus = httpStatus;
        attempt.errorClass = errorClass;
        attempt.errorMessage = truncate(errorMessage, MAX_ERROR_MESSAGE_LENGTH);
        attempt.responseBodySnippet = truncate(responseBodySnippet, MAX_BODY_SNIPPET_LENGTH);
        attempt.retryAfterSeconds = retryAfterSeconds;
        attempt.nextAttemptAt = nextAttemptAt;
        attempt.workerId = Objects.requireNonNull(workerId, "workerId");
        return attempt;
    }

    private static Integer durationMillis(Instant startedAt, Instant completedAt) {
        if (completedAt == null) {
            return null;
        }
        long millis = Duration.between(startedAt, completedAt).toMillis();
        return (int) Math.min(millis, Integer.MAX_VALUE);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    public UUID getId() {
        return id;
    }

    public UUID getDeliveryId() {
        return deliveryId;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Integer getDurationMs() {
        return durationMs;
    }

    public AttemptOutcome getOutcome() {
        return outcome;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public ErrorClass getErrorClass() {
        return errorClass;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getResponseBodySnippet() {
        return responseBodySnippet;
    }

    public Integer getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getWorkerId() {
        return workerId;
    }
}
