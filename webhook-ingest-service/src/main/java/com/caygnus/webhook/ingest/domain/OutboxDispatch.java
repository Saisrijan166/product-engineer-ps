package com.caygnus.webhook.ingest.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The intent to have an event delivered, written in the same transaction as the event itself.
 *
 * <p>This row is what makes the handoff lossless without a queue. The alternative -- accept the
 * event, commit, then call the delivery service -- has a window where the process can die with
 * the event accepted and nobody aware it needs delivering. Here there is no such window: either
 * both rows committed or neither did, and a dispatcher will find this one however long that takes.
 *
 * <p>Filled in by the dispatcher in step 10; until then every row stays {@code PENDING}.
 */
@Entity
@Table(name = "outbox_dispatch")
public class OutboxDispatch {

    /** Bounded so a verbose upstream error cannot make this row unboundedly large. */
    private static final int MAX_ERROR_LENGTH = 1024;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private String eventId;

    /** The serialised {@code CreateDeliveryRequest}: everything the dispatcher needs, in one row. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private DispatchStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_dispatch_at", nullable = false)
    private Instant nextDispatchAt;

    @Column(name = "delivery_id")
    private UUID deliveryId;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "dispatched_at")
    private Instant dispatchedAt;

    protected OutboxDispatch() {
        // For Hibernate.
    }

    /**
     * The delivery service has a delivery for this event. Done.
     *
     * <p>Recording the id closes the loop: the composed read can now answer "what happened to my
     * event" without the ingest service having to guess which delivery is the right one.
     */
    public void markDispatched(UUID acknowledgedDeliveryId, Instant now) {
        this.status = DispatchStatus.DISPATCHED;
        this.deliveryId = Objects.requireNonNull(acknowledgedDeliveryId, "deliveryId");
        this.dispatchedAt = now;
        this.lastError = null;
    }

    /**
     * The delivery service was unreachable or unwell. Try again later, unless we have tried enough.
     *
     * <p>The error is kept either way. A row that gave up after ten attempts is useless to whoever
     * is looking at it if it does not say what kept going wrong.
     *
     * @param whenDue    when the next attempt becomes due, from {@code DispatchBackoff}
     * @param maxAttempts the point at which trying again stops being worth it
     */
    public void recordFailedAttempt(String error, Instant whenDue, Instant now, int maxAttempts) {
        this.attempts++;
        this.lastError = truncate(error);
        if (attempts >= maxAttempts) {
            this.status = DispatchStatus.FAILED;
            this.nextDispatchAt = now;
        } else {
            this.nextDispatchAt = whenDue;
        }
    }

    /**
     * The delivery service rejected the request itself.
     *
     * <p>Terminal at once rather than after ten identical rejections: a 400 means we sent
     * something it will never accept, and the attempts would only delay anyone noticing.
     */
    public void markRejected(String error, Instant now) {
        this.status = DispatchStatus.FAILED;
        this.attempts++;
        this.lastError = truncate(error);
        this.nextDispatchAt = now;
    }

    public boolean isPending() {
        return status == DispatchStatus.PENDING;
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }

    public Long getId() {
        return id;
    }

    public String getEventId() {
        return eventId;
    }

    public String getPayload() {
        return payload;
    }

    public DispatchStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextDispatchAt() {
        return nextDispatchAt;
    }

    public UUID getDeliveryId() {
        return deliveryId;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDispatchedAt() {
        return dispatchedAt;
    }
}
