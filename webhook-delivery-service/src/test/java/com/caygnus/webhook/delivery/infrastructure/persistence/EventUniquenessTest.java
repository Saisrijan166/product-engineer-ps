package com.caygnus.webhook.delivery.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.caygnus.webhook.common.model.DeliveryStatus;
import com.caygnus.webhook.delivery.domain.Delivery;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Consumer-side idempotency: one delivery job per event, decided by the database.
 *
 * <p>The dispatcher is at-least-once by design -- it can crash between sending and marking a row
 * dispatched, and will then send again. That is only safe because this insert cannot produce a
 * second job, and it cannot produce one whether the repeat arrives a second later or at the same
 * instant on another instance.
 */
class EventUniquenessTest extends AbstractPersistenceTest {

    @Test
    void theFirstInsertCreatesTheDeliveryAndTheSecondQuietlyDoesNot() {
        Delivery first = newDelivery("evt_123");
        Delivery second = newDelivery("evt_123");

        assertThat(inTransaction(() -> insert(first))).isEqualTo(1);
        assertThat(inTransaction(() -> insert(second))).isZero();

        assertThat(deliveries.count()).isEqualTo(1);
        // The winner is the first one; the caller of the losing insert finds it by event id and
        // reports that existing delivery rather than the one it just tried to create.
        assertThat(deliveries.findByEventId("evt_123").orElseThrow().getId()).isEqualTo(first.getId());
    }

    @Test
    void aDuplicateIsNotAnErrorAndNeedsNoRolledBackTransaction() {
        inTransaction(() -> insert(newDelivery("evt_123")));

        // No exception to catch, so no exception-driven control flow and no transaction wasted.
        // ON CONFLICT DO NOTHING reports the outcome as a row count.
        int rowsAffected = inTransaction(() -> insert(newDelivery("evt_123")));

        assertThat(rowsAffected).isZero();
    }

    @Test
    void aNewDeliveryStartsPendingAndDueImmediately() {
        Delivery created = persistDelivery("evt_123", NOW);

        assertThat(created.getStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(created.getAttemptCount()).isZero();
        assertThat(created.getMaxAttempts()).isEqualTo(5);
        assertThat(created.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(created.getVersion()).isZero();
        assertThat(created.getPayload()).isEqualTo("{\"incidentId\": \"inc_456\"}");
    }

    @Test
    void fiftyConcurrentInsertsOfTheSameEventProduceExactlyOneDelivery() throws Exception {
        int concurrentInserts = 50;

        List<Callable<Integer>> attemptsToInsert = IntStream.range(0, concurrentInserts)
                .<Callable<Integer>>mapToObj(i -> () -> inTransaction(() -> insert(newDelivery("evt_race"))))
                .toList();

        List<Integer> rowCounts;
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            rowCounts = pool.invokeAll(attemptsToInsert).stream().map(EventUniquenessTest::get).toList();
        }

        // PostgreSQL serialises the conflicting inserts: exactly one wins, and no application lock
        // was involved in deciding which.
        assertThat(rowCounts).filteredOn(count -> count == 1).hasSize(1);
        assertThat(rowCounts).filteredOn(count -> count == 0).hasSize(concurrentInserts - 1);
        assertThat(deliveries.count()).isEqualTo(1);
    }

    @Test
    void theUniqueConstraintIsOnTheEventIdNotTheGeneratedId() {
        // Two genuinely different rows by primary key, one logical event. The primary key must not
        // be what decides this, or a second dispatch with a fresh UUID would create a second job.
        Delivery first = newDelivery("evt_123");
        Delivery second = newDelivery("evt_123");
        assertThat(first.getId()).isNotEqualTo(second.getId());

        inTransaction(() -> insert(first));
        inTransaction(() -> insert(second));

        assertThat(deliveries.findAll()).extracting(Delivery::getId).containsExactly(first.getId());
    }

    @Test
    void differentEventsGetTheirOwnDeliveries() {
        inTransaction(() -> insert(newDelivery("evt_1")));
        inTransaction(() -> insert(newDelivery("evt_2")));

        assertThat(deliveries.count()).isEqualTo(2);
        assertThat(deliveries.existsByEventId("evt_1")).isTrue();
        assertThat(deliveries.existsByEventId("evt_3")).isFalse();
    }

    private Delivery newDelivery(String eventId) {
        return Delivery.create(
                eventId,
                "incident.created",
                OffsetDateTime.parse("2026-09-15T09:59:00Z"),
                "{\"incidentId\": \"inc_456\"}",
                "http://receiver:8082/receive",
                5,
                NOW);
    }

    private static <T> T get(Future<T> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
