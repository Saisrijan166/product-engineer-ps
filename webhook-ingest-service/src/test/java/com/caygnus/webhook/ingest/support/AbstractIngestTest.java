package com.caygnus.webhook.ingest.support;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.caygnus.webhook.ingest.application.OutboxDispatcher;
import com.caygnus.webhook.ingest.infrastructure.persistence.OutboxDispatchRepository;
import com.caygnus.webhook.ingest.infrastructure.persistence.WebhookEventRepository;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Shared setup for the ingest acceptance tests: a real PostgreSQL, the real controller stack, and
 * a delivery service that can be told to fall over.
 *
 * <p>The same two substitutions as the delivery module. The {@code test} profile turns the
 * dispatcher's timer off so it advances only when a test says so -- otherwise it would be firing
 * every 500ms in the background of every test here, dispatching rows out from under assertions.
 * And the clock is mutable, so a test that needs to be nine seconds into a dispatch backoff says
 * so rather than waiting.
 *
 * <p>Everything else is real. The claim that these tests make -- that PostgreSQL settles a race
 * fifty callers cannot -- would be worth nothing against a substitute.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractIngestTest {

    /** Whole milliseconds, so assertions survive the round trip through PostgreSQL. */
    protected static final Instant START = Instant.parse("2026-09-15T10:00:00Z");

    protected static final MutableTestClock CLOCK = new MutableTestClock(START);

    @TestBean(name = "clock", methodName = "fixedTestClock")
    protected Clock clock;

    static Clock fixedTestClock() {
        return CLOCK;
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected OutboxDispatcher dispatcher;

    @Autowired
    protected WebhookEventRepository events;

    @Autowired
    protected OutboxDispatchRepository outbox;

    @Autowired
    protected JdbcTemplate jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestDatabase.bind(registry);
        // Starts the stub if it is not already up, and points the dispatcher at it. Bound for
        // every test in the module, not just the dispatch ones: if a dispatcher ever fires when
        // it should not, it hits a controlled stub rather than whatever is on port 8081.
        registry.add("webhook.delivery-service.base-url", StubDeliveryService::baseUrl);
    }

    @BeforeEach
    void resetWorld() {
        jdbc.execute("TRUNCATE TABLE outbox_dispatch, webhook_event CASCADE");
        StubDeliveryService.reset();
        CLOCK.set(START);
    }

    /** The event contract from the problem statement, verbatim. */
    protected MockHttpServletRequestBuilder postEvent(String eventId) {
        return postEvent(eventId, """
                {"incidentId": "inc_456", "severity": "high"}""");
    }

    protected MockHttpServletRequestBuilder postEvent(String eventId, String payloadJson) {
        return post("/api/v1/events")
                .contentType(APPLICATION_JSON)
                .content("""
                        {
                          "eventId": "%s",
                          "type": "incident.created",
                          "occurredAt": "2026-09-15T10:00:00Z",
                          "payload": %s
                        }""".formatted(eventId, payloadJson));
    }

    protected int duplicateSubmissionCountOf(String eventId) {
        return events.findByEventId(eventId).orElseThrow().getDuplicateSubmissionCount();
    }
}
