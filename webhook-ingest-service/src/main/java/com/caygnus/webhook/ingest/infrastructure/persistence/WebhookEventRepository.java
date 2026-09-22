package com.caygnus.webhook.ingest.infrastructure.persistence;

import com.caygnus.webhook.ingest.domain.WebhookEvent;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The accepted-events table.
 *
 * <p>Both writes are native and both lean on PostgreSQL to settle a race that would otherwise need
 * a lock. Neither is an optimisation: between them they are AC4.
 */
public interface WebhookEventRepository extends JpaRepository<WebhookEvent, UUID> {

    Optional<WebhookEvent> findByEventId(String eventId);

    Page<WebhookEvent> findAllByOrderByReceivedAtDesc(Pageable pageable);

    /**
     * Accept an event unless its identifier has been seen before.
     *
     * <p>One statement, one round trip, and no way for two callers to both win. There is no
     * read-then-write to race, no lock to acquire and no constraint violation to catch -- a
     * duplicate is reported as a row count of zero, which is data rather than an exception, so
     * fifty simultaneous resubmissions cost forty-nine trivial no-ops instead of forty-nine
     * rolled-back transactions.
     *
     * @return 1 if this call accepted the event, 0 if it was already known
     */
    @Modifying
    @Query(value = """
            INSERT INTO webhook_event (
                id, event_id, type, occurred_at, payload, payload_hash, received_at,
                duplicate_submission_count)
            VALUES (
                :id, :eventId, :type, :occurredAt, CAST(:payload AS jsonb), :payloadHash, :receivedAt, 0)
            ON CONFLICT (event_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("eventId") String eventId,
            @Param("type") String type,
            @Param("occurredAt") OffsetDateTime occurredAt,
            @Param("payload") String payload,
            @Param("payloadHash") String payloadHash,
            @Param("receivedAt") Instant receivedAt);

    /**
     * Count one more submission of an event we already hold -- but only if it really is the same
     * event.
     *
     * <p>The hash is in the {@code WHERE} clause rather than checked beforehand, so the comparison
     * and the increment are one atomic statement. Checking first would leave a window in which the
     * row could change between the two, and would need a lock to close.
     *
     * <p>{@code count + 1} is evaluated by PostgreSQL against the row it holds a lock on, so
     * concurrent resubmissions queue up and each one is counted. Reading the value into Java and
     * writing it back would lose all but one of them.
     *
     * @return 1 if this was a genuine resubmission, 0 if the payload differs or the event is gone
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE webhook_event
               SET duplicate_submission_count = duplicate_submission_count + 1,
                   last_duplicate_at = :now
             WHERE event_id = :eventId
               AND payload_hash = :payloadHash
            """, nativeQuery = true)
    int recordDuplicateSubmission(
            @Param("eventId") String eventId,
            @Param("payloadHash") String payloadHash,
            @Param("now") Instant now);
}
