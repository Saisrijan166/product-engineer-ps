package com.caygnus.webhook.delivery.infrastructure.persistence;

import com.caygnus.webhook.common.model.DeliveryStatus;
import com.caygnus.webhook.delivery.domain.Delivery;
import com.caygnus.webhook.delivery.domain.DeliveryStateMachine;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The delivery table, used as both the store of record and the work queue.
 *
 * <p>Three queries here are native on purpose, because each one depends on a PostgreSQL behaviour
 * that JPQL cannot express: {@code ON CONFLICT DO NOTHING}, and {@code FOR UPDATE SKIP LOCKED}.
 * Those are not optimisations -- they are the idempotency and the concurrency control.
 */
public interface DeliveryRepository extends JpaRepository<Delivery, UUID> {

    Optional<Delivery> findByEventId(String eventId);

    boolean existsByEventId(String eventId);

    /** The dead-letter view. Backed by idx_delivery_status_created; limited in SQL, never in Java. */
    List<Delivery> findByStatusOrderByCreatedAtDesc(DeliveryStatus status, Pageable pageable);

    List<Delivery> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * Create a delivery unless one already exists for this event.
     *
     * <p>The database arbitrates the race, not the application: concurrent dispatches of the same
     * event produce one winner and no rolled-back transactions, without a lock or a caught
     * constraint violation anywhere. This is what makes "does not schedule a second independent
     * delivery job" structural rather than careful.
     *
     * @return 1 if this call created the delivery, 0 if one already existed
     */
    @Modifying
    @Query(value = """
            INSERT INTO delivery (
                id, event_id, event_type, occurred_at, payload, target_url,
                status, attempt_count, max_attempts, next_attempt_at, created_at, version)
            VALUES (
                :id, :eventId, :eventType, :occurredAt, CAST(:payload AS jsonb), :targetUrl,
                'PENDING', 0, :maxAttempts, :nextAttemptAt, :createdAt, 0)
            ON CONFLICT (event_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("eventId") String eventId,
            @Param("eventType") String eventType,
            @Param("occurredAt") OffsetDateTime occurredAt,
            @Param("payload") String payload,
            @Param("targetUrl") String targetUrl,
            @Param("maxAttempts") int maxAttempts,
            @Param("nextAttemptAt") Instant nextAttemptAt,
            @Param("createdAt") Instant createdAt);

    /**
     * Lock the deliveries that are due, skipping any another instance is already holding.
     *
     * <p>{@code SKIP LOCKED} is what lets every instance poll the same index with no coordination
     * and no chance of two workers taking the same delivery: a locked row is not contended for, it
     * is passed over. The locks live until the calling transaction commits, so the caller must load
     * and claim inside that same transaction -- see {@link #findAllById}.
     *
     * <p>Only ids are selected. The rows are locked either way, and loading entities is the
     * caller's business once it knows which ones it won.
     *
     * @param limit never more than the executor has free capacity, so unclaimed work waits in
     *              PostgreSQL rather than in a queue in memory
     */
    @Query(value = """
            SELECT id
              FROM delivery
             WHERE status IN (:claimableStatuses)
               AND next_attempt_at <= :now
             ORDER BY next_attempt_at
             LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<UUID> lockDueDeliveryIds(
            @Param("claimableStatuses") Collection<String> claimableStatuses,
            @Param("now") Instant now,
            @Param("limit") int limit);

    /**
     * Lock in-flight deliveries whose lease has run out -- the ones whose worker died mid-attempt.
     *
     * <p>Also {@code SKIP LOCKED}: a delivery another instance is actively finishing is not this
     * instance's problem, and waiting for it would be exactly wrong.
     */
    @Query(value = """
            SELECT id
              FROM delivery
             WHERE status = 'IN_FLIGHT'
               AND lease_expires_at <= :now
             ORDER BY lease_expires_at
             LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<UUID> lockExpiredLeaseIds(@Param("now") Instant now, @Param("limit") int limit);

    /** How many deliveries are past due -- the real queue depth, for the backlog gauge. */
    @Query(value = """
            SELECT count(*)
              FROM delivery
             WHERE status IN (:claimableStatuses)
               AND next_attempt_at <= :now
            """, nativeQuery = true)
    long countDue(@Param("claimableStatuses") Collection<String> claimableStatuses, @Param("now") Instant now);

    /**
     * The claimable states, as the database spells them. Taken from {@link DeliveryStateMachine} so
     * the queries above and the transition table cannot drift apart -- the attempt bound holds
     * because a terminal delivery is invisible here, and that must stay true by construction.
     */
    static Collection<String> claimableStatusNames() {
        Set<DeliveryStatus> claimable = DeliveryStateMachine.claimableStates();
        return claimable.stream().map(Enum::name).toList();
    }

    default List<UUID> lockDueDeliveryIds(Instant now, int limit) {
        return lockDueDeliveryIds(claimableStatusNames(), now, limit);
    }

    default long countDue(Instant now) {
        return countDue(claimableStatusNames(), now);
    }
}
