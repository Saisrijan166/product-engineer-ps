package com.caygnus.webhook.delivery.infrastructure.persistence;

import com.caygnus.webhook.delivery.domain.Delivery;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.caygnus.webhook.delivery.support.TestDatabase;

/**
 * One real PostgreSQL, shared by every persistence test in the module.
 *
 * <p>See {@link TestDatabase} for which server that is and why it is never H2.
 *
 * <p>{@code Propagation.NOT_SUPPORTED} turns off the transaction {@link DataJpaTest} would
 * otherwise wrap each test in. These tests are about what happens <em>between</em> transactions --
 * two of them racing, one of them holding row locks, a stale entity meeting a committed one -- so a
 * single enclosing transaction that rolls back at the end would hide the very thing under test.
 * Each test cleans up after itself instead.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
abstract class AbstractPersistenceTest {

    /** Whole milliseconds: PostgreSQL stores microseconds, so a nanosecond-precision Instant would
     *  not survive a round trip and the assertions would be about rounding rather than behaviour. */
    static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    static final String WORKER = "delivery-test:1:a3f";

    @Autowired
    protected DeliveryRepository deliveries;

    @Autowired
    protected DeliveryAttemptRepository attempts;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected PlatformTransactionManager transactionManager;

    protected TransactionTemplate transactionTemplate;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        TestDatabase.bind(registry);
    }

    @BeforeEach
    void resetSchema() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        // TRUNCATE rather than deleteAll: it also resets the planner statistics that ClaimIndexTest
        // deliberately builds up, so test order cannot influence a query plan.
        jdbc.execute("TRUNCATE TABLE delivery_attempt, delivery CASCADE");
    }

    protected <T> T inTransaction(Supplier<T> work) {
        return transactionTemplate.execute(status -> work.get());
    }

    protected void inTransaction(Runnable work) {
        transactionTemplate.executeWithoutResult(status -> work.run());
    }

    /** A delivery persisted through the production insert path, due at {@code dueAt}. */
    protected Delivery persistDelivery(String eventId, Instant dueAt) {
        Delivery delivery = Delivery.create(
                eventId,
                "incident.created",
                OffsetDateTime.parse("2026-09-15T09:59:00Z"),
                "{\"incidentId\":\"inc_456\"}",
                "http://receiver:8082/receive",
                5,
                dueAt);
        inTransaction(() -> insert(delivery));
        return deliveries.findByEventId(eventId).orElseThrow();
    }

    protected int insert(Delivery delivery) {
        return deliveries.insertIfAbsent(
                delivery.getId(),
                delivery.getEventId(),
                delivery.getEventType(),
                delivery.getOccurredAt(),
                delivery.getPayload(),
                delivery.getTargetUrl(),
                delivery.getMaxAttempts(),
                delivery.getNextAttemptAt(),
                delivery.getCreatedAt());
    }
}
