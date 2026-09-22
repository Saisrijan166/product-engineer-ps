package com.caygnus.webhook.delivery.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Guards the two partial indexes the queue runs on.
 *
 * <p>Both hot queries are index-backed by design, not by accident: the claim runs on every tick of
 * every instance, and the reaper's sweep on every tenth second. Completed deliveries accumulate
 * forever, so a sequential scan here degrades quietly and continuously -- fast in a demo, and worse
 * every week in production. That is exactly the kind of regression nothing else in the suite would
 * notice, because a seq scan returns perfectly correct rows.
 *
 * <p>The table is loaded with mostly-finished deliveries and analysed first, so the planner is
 * choosing on realistic statistics rather than being forced.
 */
class ClaimIndexTest extends AbstractPersistenceTest {

    private static final int FINISHED_DELIVERIES = 5_000;
    private static final int DUE_DELIVERIES = 20;
    private static final int IN_FLIGHT_DELIVERIES = 10;

    /** Must mirror {@link DeliveryRepository#lockDueDeliveryIds}; the plan is only meaningful if it does. */
    private static final String CLAIM_QUERY = """
            SELECT id
              FROM delivery
             WHERE status IN ('PENDING', 'RETRY_SCHEDULED')
               AND next_attempt_at <= now()
             ORDER BY next_attempt_at
             LIMIT 8
            FOR UPDATE SKIP LOCKED
            """;

    /** Must mirror {@link DeliveryRepository#lockExpiredLeaseIds}. */
    private static final String REAPER_QUERY = """
            SELECT id
              FROM delivery
             WHERE status = 'IN_FLIGHT'
               AND lease_expires_at <= now()
             ORDER BY lease_expires_at
             LIMIT 100
            FOR UPDATE SKIP LOCKED
            """;

    @BeforeEach
    void loadRealisticVolume() {
        // The overwhelming majority of rows are history: succeeded, not due, not leased. This is
        // what makes the partial indexes worth having -- they cover only the live tail.
        jdbc.update("""
                INSERT INTO delivery (id, event_id, target_url, status, attempt_count, max_attempts,
                                      next_attempt_at, created_at, completed_at, last_outcome, version)
                SELECT gen_random_uuid(), 'evt_done_' || i, 'http://receiver:8082/receive',
                       'SUCCEEDED', 1, 5, NULL, now() - (i * interval '1 second'),
                       now() - (i * interval '1 second'), 'SUCCESS', 1
                  FROM generate_series(1, ?) AS i
                """, FINISHED_DELIVERIES);

        jdbc.update("""
                INSERT INTO delivery (id, event_id, target_url, status, attempt_count, max_attempts,
                                      next_attempt_at, created_at, version)
                SELECT gen_random_uuid(), 'evt_due_' || i, 'http://receiver:8082/receive',
                       'PENDING', 0, 5, now() - interval '1 minute', now(), 0
                  FROM generate_series(1, ?) AS i
                """, DUE_DELIVERIES);

        jdbc.update("""
                INSERT INTO delivery (id, event_id, target_url, status, attempt_count, max_attempts,
                                      next_attempt_at, lease_owner, lease_expires_at, created_at, version)
                SELECT gen_random_uuid(), 'evt_flight_' || i, 'http://receiver:8082/receive',
                       'IN_FLIGHT', 1, 5, NULL, 'worker-' || i, now() - interval '1 minute', now(), 1
                  FROM generate_series(1, ?) AS i
                """, IN_FLIGHT_DELIVERIES);

        jdbc.execute("ANALYZE delivery");
    }

    @Test
    void theClaimQueryUsesThePartialDueIndex() {
        String plan = explain(CLAIM_QUERY);

        assertThat(plan)
                .as("the claim runs on every tick of every instance:%n%s", plan)
                .contains("idx_delivery_due")
                .doesNotContain("Seq Scan");
    }

    @Test
    void theReaperQueryUsesThePartialLeaseIndex() {
        String plan = explain(REAPER_QUERY);

        assertThat(plan)
                .as("crash recovery must not degrade as history accumulates:%n%s", plan)
                .contains("idx_delivery_lease_expiry")
                .doesNotContain("Seq Scan");
    }

    @Test
    void thePartialIndexesCoverOnlyTheLiveTailNotTheWholeTable() {
        assertThat(deliveries.count()).isEqualTo(FINISHED_DELIVERIES + DUE_DELIVERIES + IN_FLIGHT_DELIVERIES);

        // The point of making them partial: 30 live rows out of 5030 are indexed, so the queue's
        // working set stays the same size no matter how much history accrues. Compared by size
        // rather than by counting rows, because counting them would just restate the predicate.
        long tableSize = relationSize("delivery");
        assertThat(relationSize("idx_delivery_due")).isLessThan(tableSize / 10);
        assertThat(relationSize("idx_delivery_lease_expiry")).isLessThan(tableSize / 10);

        // Whereas the unfiltered listing index necessarily carries every row.
        assertThat(relationSize("idx_delivery_status_created")).isGreaterThan(tableSize / 10);
    }

    @Test
    void theDeadLetterViewIsIndexedToo() {
        String plan = explain("SELECT id FROM delivery WHERE status = 'FAILED_EXHAUSTED' ORDER BY created_at DESC");

        assertThat(plan).as("%s", plan).contains("idx_delivery_status_created");
    }

    private String explain(String query) {
        return String.join("\n", jdbc.queryForList("EXPLAIN " + query, String.class));
    }

    private long relationSize(String relation) {
        return jdbc.queryForObject("SELECT pg_relation_size(?::regclass)", Long.class, relation);
    }
}
