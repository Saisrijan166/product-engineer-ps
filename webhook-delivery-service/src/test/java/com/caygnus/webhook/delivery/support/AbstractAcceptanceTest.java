package com.caygnus.webhook.delivery.support;

import com.caygnus.webhook.common.api.CreateDeliveryRequest;
import com.caygnus.webhook.delivery.application.CreateDeliveryUseCase;
import com.caygnus.webhook.delivery.application.DeliveryQueryService;
import com.caygnus.webhook.delivery.application.DeliveryScheduler;
import com.caygnus.webhook.delivery.domain.Delivery;
import com.caygnus.webhook.delivery.domain.DeliveryAttempt;
import com.caygnus.webhook.delivery.infrastructure.health.EndpointHealthGate;
import com.caygnus.webhook.delivery.infrastructure.persistence.DeliveryAttemptRepository;
import com.caygnus.webhook.delivery.infrastructure.persistence.DeliveryRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The setup that makes the acceptance tests deterministic.
 *
 * <p>Three substitutions, all of them from ARCHITECTURE.md 12.1, and together they remove every
 * reason for these tests to be flaky:
 *
 * <ul>
 *   <li>The {@code test} profile turns the {@code @Scheduled} trigger off, so the queue advances
 *       only when a test says {@code scheduler.runOnce()} -- never between an action and its
 *       assertion.
 *   <li>That same profile swaps the worker pool for a synchronous executor, so {@code runOnce()}
 *       returns once every delivery it claimed has finished. There is nothing to wait for.
 *   <li>The {@link Clock} becomes a {@link MutableTestClock}, so a test that needs to be
 *       twenty-five seconds into a backoff simply says so.
 * </ul>
 *
 * <p>What is <em>not</em> substituted matters just as much: a real PostgreSQL, the real scheduler,
 * the real HTTP client over a real socket, and the real receiver. The parts being proven are the
 * production ones; only time and the decision of when to tick are taken away from them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
public abstract class AbstractAcceptanceTest {

    /** Whole milliseconds, so assertions survive the round trip through PostgreSQL. */
    protected static final Instant START = Instant.parse("2026-09-15T10:00:00Z");

    protected static final MutableTestClock CLOCK = new MutableTestClock(START);

    /**
     * Replaces the production {@code Clock.systemUTC()} for the whole context.
     *
     * <p>{@code @TestBean} swaps the definition outright rather than registering a competing one,
     * so there is no second {@link Clock} and no reliance on bean-override ordering -- which is
     * exactly how an earlier attempt at this quietly went on using the wall clock.
     */
    @TestBean(name = "clock", methodName = "fixedTestClock")
    protected Clock clock;

    static Clock fixedTestClock() {
        return CLOCK;
    }

    @Autowired
    protected DeliveryScheduler scheduler;

    @Autowired
    protected CreateDeliveryUseCase createDelivery;

    @Autowired
    protected DeliveryQueryService queries;

    @Autowired
    protected DeliveryRepository deliveries;

    @Autowired
    protected DeliveryAttemptRepository attempts;

    @Autowired
    protected EndpointHealthGate healthGate;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** For the few fixtures that need a transaction of their own; the tests are not transactional. */
    protected TransactionTemplate inTransaction;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestDatabase.bind(registry);
        // Starts the receiver if it is not already up, and points the engine at it.
        registry.add("webhook.delivery.target-url", TestReceiver::receiveUrl);
    }

    @BeforeEach
    void resetWorld() {
        inTransaction = new TransactionTemplate(transactionManager);
        jdbc.execute("TRUNCATE TABLE delivery_attempt, delivery CASCADE");
        TestReceiver.reset();
        CLOCK.set(START);
        // The gate is the only load-bearing in-memory state in this service, and Spring caches
        // contexts across test classes -- so without this, one class's failing endpoint becomes
        // another class's mystery.
        healthGate.reset();
    }

    /** Create a delivery the way the ingest service would, through the real use case. */
    protected UUID createDelivery(String eventId) {
        return createDelivery.create(new CreateDeliveryRequest(
                eventId,
                "incident.created",
                OffsetDateTime.parse("2026-09-15T10:00:00Z"),
                Map.of("incidentId", "inc_456", "severity", "high"))).deliveryId();
    }

    /**
     * A delivery aimed somewhere other than the test receiver.
     *
     * <p>Goes through the same insert the use case uses, but names the target itself -- which the
     * schema allows because {@code target_url} is snapshotted per delivery rather than read from
     * configuration at attempt time. That is what lets a transport failure be tested against a
     * genuinely dead port without reconfiguring the whole context.
     */
    protected UUID createDeliveryTo(String eventId, String targetUrl) {
        Delivery delivery = Delivery.create(
                eventId, "incident.created", OffsetDateTime.parse("2026-09-15T10:00:00Z"),
                "{\"incidentId\": \"inc_456\"}", targetUrl, 5, CLOCK.instant());
        inTransaction.executeWithoutResult(status -> deliveries.insertIfAbsent(
                delivery.getId(), delivery.getEventId(), delivery.getEventType(), delivery.getOccurredAt(),
                delivery.getPayload(), delivery.getTargetUrl(), delivery.getMaxAttempts(),
                delivery.getNextAttemptAt(), delivery.getCreatedAt()));
        return delivery.getId();
    }

    /** A port nothing is listening on, for proving that a refused connection is retried. */
    protected static int closedPort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not reserve a port to close", e);
        }
    }

    protected Delivery reload(UUID deliveryId) {
        return deliveries.findById(deliveryId).orElseThrow();
    }

    protected List<DeliveryAttempt> historyOf(UUID deliveryId) {
        return attempts.findByDeliveryIdOrderByAttemptNumberAsc(deliveryId);
    }
}
