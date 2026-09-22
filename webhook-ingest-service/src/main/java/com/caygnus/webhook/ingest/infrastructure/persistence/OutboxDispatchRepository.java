package com.caygnus.webhook.ingest.infrastructure.persistence;

import com.caygnus.webhook.ingest.domain.OutboxDispatch;
import com.caygnus.webhook.ingest.domain.DispatchStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The transactional outbox. Written with the event; drained by the dispatcher in step 10. */
public interface OutboxDispatchRepository extends JpaRepository<OutboxDispatch, Long> {

    Optional<OutboxDispatch> findByEventId(String eventId);

    List<OutboxDispatch> findByStatus(DispatchStatus status);

    /**
     * Lock the dispatch intents that are due, skipping any another instance already holds.
     *
     * <p>{@code SKIP LOCKED} for the same reason the delivery queue uses it: several ingest
     * instances can poll this table at once and come away with disjoint sets, with no coordinator
     * between them. Ordered by id, so the oldest intent goes first and a backlog drains in the
     * order it accumulated.
     */
    @Query(value = """
            SELECT id
              FROM outbox_dispatch
             WHERE status = 'PENDING'
               AND next_dispatch_at <= :now
             ORDER BY id
             LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Long> lockPendingIds(@Param("now") Instant now, @Param("limit") int limit);

    /** How many intents are waiting to go out -- the backlog gauge for step 13. */
    @Query(value = "SELECT count(*) FROM outbox_dispatch WHERE status = 'PENDING'", nativeQuery = true)
    long countPending();

    /**
     * Record the intent to deliver this event, unless it is already recorded.
     *
     * <p>{@code ON CONFLICT DO NOTHING} here is belt and braces rather than the main defence --
     * this only runs when the event insert above reported a genuinely new event, so a conflict
     * should be impossible. It costs nothing, and it means the unique constraint can never turn
     * an odd interleaving into a 500 for the caller.
     */
    @Modifying
    @Query(value = """
            INSERT INTO outbox_dispatch (
                event_id, payload, status, attempts, next_dispatch_at, created_at)
            VALUES (
                :eventId, CAST(:payload AS jsonb), 'PENDING', 0, :now, :now)
            ON CONFLICT (event_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("eventId") String eventId,
            @Param("payload") String payload,
            @Param("now") Instant now);
}
