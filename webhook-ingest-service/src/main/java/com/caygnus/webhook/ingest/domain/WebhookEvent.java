package com.caygnus.webhook.ingest.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * An event this service has accepted responsibility for.
 *
 * <p>Retained before anything is scheduled, which is the first of the problem statement's required
 * behaviours: once a caller has a 201, the event exists whatever happens to the process next.
 *
 * <p>There are no mutators. The duplicate counter is incremented by a single atomic {@code UPDATE}
 * rather than by reading, adding one and writing back -- under fifty concurrent resubmissions the
 * read-modify-write would lose increments, and the count is the evidence that idempotency worked.
 */
@Entity
@Table(name = "webhook_event")
public class WebhookEvent {

    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private String eventId;

    @Column(name = "type", nullable = false, updatable = false)
    private String type;

    @Column(name = "occurred_at", updatable = false)
    private OffsetDateTime occurredAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false)
    private String payload;

    @Column(name = "payload_hash", nullable = false, updatable = false, length = 64)
    private String payloadHash;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "duplicate_submission_count", nullable = false)
    private int duplicateSubmissionCount;

    @Column(name = "last_duplicate_at")
    private Instant lastDuplicateAt;

    protected WebhookEvent() {
        // For Hibernate and for the static factory below.
    }

    public static WebhookEvent accept(
            String eventId,
            String type,
            OffsetDateTime occurredAt,
            String payload,
            String payloadHash,
            Instant receivedAt) {

        WebhookEvent event = new WebhookEvent();
        event.id = UUID.randomUUID();
        event.eventId = eventId;
        event.type = type;
        event.occurredAt = occurredAt;
        event.payload = payload;
        event.payloadHash = payloadHash;
        event.receivedAt = receivedAt;
        event.duplicateSubmissionCount = 0;
        return event;
    }

    public boolean hasSamePayloadAs(String otherPayloadHash) {
        return payloadHash.equals(otherPayloadHash);
    }

    public UUID getId() {
        return id;
    }

    public String getEventId() {
        return eventId;
    }

    public String getType() {
        return type;
    }

    public OffsetDateTime getOccurredAt() {
        return occurredAt;
    }

    public String getPayload() {
        return payload;
    }

    public String getPayloadHash() {
        return payloadHash;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public int getDuplicateSubmissionCount() {
        return duplicateSubmissionCount;
    }

    public Instant getLastDuplicateAt() {
        return lastDuplicateAt;
    }
}
