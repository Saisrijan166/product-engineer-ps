package com.caygnus.webhook.delivery.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.caygnus.webhook.common.api.DeliveryView;
import com.caygnus.webhook.common.model.AttemptOutcome;
import com.caygnus.webhook.delivery.support.TestDatabase;
import com.caygnus.webhook.delivery.domain.Delivery;
import com.caygnus.webhook.delivery.domain.DeliveryAttempt;
import com.caygnus.webhook.delivery.infrastructure.persistence.DeliveryAttemptRepository;
import com.caygnus.webhook.delivery.infrastructure.persistence.DeliveryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The delivery service's API, end to end through a real PostgreSQL.
 *
 * <p>The headline is {@link #aSecondPostForTheSameEventReturnsTheSameDeliveryAndCreatesNoSecondRow}
 * -- step 5's exit criterion, and the guarantee the whole outbox handoff depends on. Everything
 * else here exists so a reviewer can see the state and history this service exposes, which is what
 * AC5 is judged on.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        // These tests are about the API surface, not the queue. Spring caches contexts across test
        // classes, so a scheduler left ticking here would go on claiming other classes' deliveries
        // long after this one finished.
        properties = "webhook.scheduler.enabled=false")
@AutoConfigureMockMvc
class DeliveryApiTest {

    private static final String WORKER = "delivery-test:1:a3f";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private DeliveryRepository deliveries;

    @Autowired
    private DeliveryAttemptRepository attempts;

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        TestDatabase.bind(registry);
    }

    @BeforeEach
    void resetSchema() {
        jdbc.execute("TRUNCATE TABLE delivery_attempt, delivery CASCADE");
    }

    // ---------------------------------------------------------------- creation

    @Test
    void aFirstPostCreatesTheDelivery() throws Exception {
        mockMvc.perform(postDelivery("evt_123"))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.eventId").value("evt_123"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.attemptCount").value(0))
                .andExpect(jsonPath("$.maxAttempts").value(5))
                .andExpect(jsonPath("$.nextAttemptAt").exists())
                .andExpect(jsonPath("$.terminalReason").doesNotExist())
                .andExpect(jsonPath("$.attempts").isEmpty());

        assertThat(deliveries.count()).isEqualTo(1);
    }

    /** Step 5's exit criterion. */
    @Test
    void aSecondPostForTheSameEventReturnsTheSameDeliveryAndCreatesNoSecondRow() throws Exception {
        MvcResult first = mockMvc.perform(postDelivery("evt_123"))
                .andExpect(status().isCreated())
                .andReturn();
        UUID firstDeliveryId = deliveryIdOf(first);

        MvcResult second = mockMvc.perform(postDelivery("evt_123"))
                // 200, not 201: the delivery exists, this call did not create it.
                .andExpect(status().isOk())
                .andReturn();

        assertThat(deliveryIdOf(second)).isEqualTo(firstDeliveryId);
        assertThat(deliveries.count()).isEqualTo(1);
        assertThat(deliveries.findByEventId("evt_123").orElseThrow().getId()).isEqualTo(firstDeliveryId);
    }

    @Test
    void aRepeatedPostDoesNotDisturbADeliveryAlreadyInProgress() throws Exception {
        MvcResult created = mockMvc.perform(postDelivery("evt_123")).andReturn();
        UUID deliveryId = deliveryIdOf(created);
        spendOneAttemptOn(deliveryId);

        mockMvc.perform(postDelivery("evt_123"))
                .andExpect(status().isOk())
                // The attempt budget is not refunded and the schedule is not reset. A dispatcher
                // that retries mid-flight must not restart a delivery that is already running.
                .andExpect(jsonPath("$.attemptCount").value(1))
                .andExpect(jsonPath("$.status").value("RETRY_SCHEDULED"));
    }

    @Test
    void twentyConcurrentPostsOfTheSameEventProduceExactlyOneDelivery() throws Exception {
        int concurrentPosts = 20;

        List<Callable<Integer>> posts = IntStream.range(0, concurrentPosts)
                .<Callable<Integer>>mapToObj(i -> () ->
                        mockMvc.perform(postDelivery("evt_race")).andReturn().getResponse().getStatus())
                .toList();

        List<Integer> statuses;
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            statuses = pool.invokeAll(posts).stream().map(DeliveryApiTest::awaitResult).toList();
        }

        // PostgreSQL decides the winner. Exactly one caller is told it created the delivery.
        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == 200).hasSize(concurrentPosts - 1);
        assertThat(deliveries.count()).isEqualTo(1);
    }

    @Test
    void differentEventsGetDifferentDeliveries() throws Exception {
        mockMvc.perform(postDelivery("evt_1")).andExpect(status().isCreated());
        mockMvc.perform(postDelivery("evt_2")).andExpect(status().isCreated());

        assertThat(deliveries.count()).isEqualTo(2);
    }

    @Test
    void theTargetUrlIsSnapshottedOntoTheDelivery() throws Exception {
        mockMvc.perform(postDelivery("evt_123"))
                .andExpect(jsonPath("$.targetUrl").value("http://localhost:8082/receive"));
    }

    // ---------------------------------------------------------- idempotency key

    @Test
    void anIdempotencyKeyMatchingTheEventIdIsAccepted() throws Exception {
        mockMvc.perform(postDelivery("evt_123").header("Idempotency-Key", "evt_123"))
                .andExpect(status().isCreated());
    }

    @Test
    void anIdempotencyKeyDisagreeingWithTheBodyIsRejected() throws Exception {
        mockMvc.perform(postDelivery("evt_123").header("Idempotency-Key", "evt_999"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:caygnus:webhook:problem:idempotency-key-mismatch"));

        // Rejected before anything was written: guessing which event the caller meant could deliver
        // the wrong payload.
        assertThat(deliveries.count()).isZero();
    }

    @Test
    void theIdempotencyKeyHeaderIsOptional() throws Exception {
        mockMvc.perform(postDelivery("evt_123")).andExpect(status().isCreated());
    }

    // ------------------------------------------------------------------- reads

    @Test
    void aDeliveryCanBeFoundByEventId() throws Exception {
        UUID deliveryId = deliveryIdOf(mockMvc.perform(postDelivery("evt_123")).andReturn());

        mockMvc.perform(get("/internal/v1/deliveries").param("eventId", "evt_123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.deliveries[0].deliveryId").value(deliveryId.toString()))
                // Histories are left out of listings unless asked for.
                .andExpect(jsonPath("$.deliveries[0].attempts").doesNotExist());
    }

    @Test
    void anEventWithNoDeliveryIsAnEmptyListNotAnError() throws Exception {
        // The ingest service's composed read has to render this, and it is a normal state.
        mockMvc.perform(get("/internal/v1/deliveries").param("eventId", "evt_missing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void theEventLookupCanInlineTheHistoryForTheComposedRead() throws Exception {
        UUID deliveryId = deliveryIdOf(mockMvc.perform(postDelivery("evt_123")).andReturn());
        spendOneAttemptOn(deliveryId);

        mockMvc.perform(get("/internal/v1/deliveries")
                        .param("eventId", "evt_123")
                        .param("includeAttempts", "true"))
                .andExpect(jsonPath("$.deliveries[0].attempts.length()").value(1));
    }

    @Test
    void aDeliveryCanBeFetchedByIdWithItsOrderedHistory() throws Exception {
        UUID deliveryId = deliveryIdOf(mockMvc.perform(postDelivery("evt_123")).andReturn());
        recordAttempts(deliveryId, 3);

        mockMvc.perform(get("/internal/v1/deliveries/{id}", deliveryId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attempts.length()").value(3))
                .andExpect(jsonPath("$.attempts[0].attemptNumber").value(1))
                .andExpect(jsonPath("$.attempts[1].attemptNumber").value(2))
                .andExpect(jsonPath("$.attempts[2].attemptNumber").value(3));
    }

    @Test
    void theAttemptsEndpointReturnsTheHistoryOldestFirst() throws Exception {
        UUID deliveryId = deliveryIdOf(mockMvc.perform(postDelivery("evt_123")).andReturn());
        recordAttempts(deliveryId, 3);

        mockMvc.perform(get("/internal/v1/deliveries/{id}/attempts", deliveryId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].attemptNumber").value(1))
                .andExpect(jsonPath("$[0].outcome").value("RETRYABLE_FAILURE"))
                .andExpect(jsonPath("$[0].httpStatus").value(503))
                .andExpect(jsonPath("$[0].workerId").value(WORKER))
                .andExpect(jsonPath("$[2].attemptNumber").value(3));
    }

    @Test
    void theDeadLetterViewListsOnlyExhaustedDeliveries() throws Exception {
        UUID exhausted = deliveryIdOf(mockMvc.perform(postDelivery("evt_dead")).andReturn());
        exhaust(exhausted);
        mockMvc.perform(postDelivery("evt_live")).andExpect(status().isCreated());

        mockMvc.perform(get("/internal/v1/deliveries").param("status", "FAILED_EXHAUSTED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.deliveries[0].deliveryId").value(exhausted.toString()))
                .andExpect(jsonPath("$.deliveries[0].terminalReason").value("ATTEMPTS_EXHAUSTED"))
                .andExpect(jsonPath("$.deliveries[0].attemptCount").value(5));
    }

    @Test
    void aListingIsBoundedEvenWhenTheCallerAsksForMore() throws Exception {
        for (int i = 1; i <= 5; i++) {
            mockMvc.perform(postDelivery("evt_" + i)).andExpect(status().isCreated());
        }

        mockMvc.perform(get("/internal/v1/deliveries").param("limit", "2"))
                .andExpect(jsonPath("$.count").value(2));
        mockMvc.perform(get("/internal/v1/deliveries").param("limit", "100000"))
                .andExpect(jsonPath("$.count").value(5));
    }

    // -------------------------------------------------------------- problem+json

    @Test
    void anUnknownDeliveryIsANotFoundProblem() throws Exception {
        mockMvc.perform(get("/internal/v1/deliveries/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:caygnus:webhook:problem:delivery-not-found"))
                .andExpect(jsonPath("$.title").value("Delivery not found"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void askingForTheHistoryOfAnUnknownDeliveryIsAlsoNotFound() throws Exception {
        // Distinct from "this delivery has not been attempted yet", which is an empty list.
        mockMvc.perform(get("/internal/v1/deliveries/{id}/attempts", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void aMissingEventIdIsARejectedRequestNamingTheField() throws Exception {
        mockMvc.perform(post("/internal/v1/deliveries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventType":"incident.created","payload":{"severity":"high"}}"""))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:caygnus:webhook:problem:validation-failed"))
                .andExpect(jsonPath("$.errors.eventId").exists());
    }

    @Test
    void aMissingPayloadIsRejected() throws Exception {
        mockMvc.perform(post("/internal/v1/deliveries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId":"evt_123","eventType":"incident.created"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.payload").exists());
    }

    @Test
    void anUnparseableIdIsARejectedParameterNotAServerError() throws Exception {
        mockMvc.perform(get("/internal/v1/deliveries/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:caygnus:webhook:problem:invalid-query"));
    }

    @Test
    void anUnknownStatusFilterIsRejected() throws Exception {
        mockMvc.perform(get("/internal/v1/deliveries").param("status", "NOPE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:caygnus:webhook:problem:invalid-query"));
    }

    // ----------------------------------------------------------------- helpers

    private MockHttpServletRequestBuilder postDelivery(String eventId) {
        return post("/internal/v1/deliveries")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "eventId": "%s",
                          "eventType": "incident.created",
                          "occurredAt": "2026-09-15T10:00:00Z",
                          "payload": {"incidentId": "inc_456", "severity": "high"}
                        }""".formatted(eventId));
    }

    private UUID deliveryIdOf(MvcResult result) throws Exception {
        return json.readValue(result.getResponse().getContentAsString(), DeliveryView.class).deliveryId();
    }

    /** Drive a delivery through one failed attempt, using the domain rather than raw SQL. */
    private void spendOneAttemptOn(UUID deliveryId) {
        Delivery delivery = deliveries.findById(deliveryId).orElseThrow();
        Instant now = Instant.now();
        delivery.claim(WORKER, now, Duration.ofSeconds(15));
        delivery.recordRetryableFailure(now, now.plusSeconds(5));
        deliveries.save(delivery);
        attempts.save(attempt(deliveryId, 1));
    }

    private void exhaust(UUID deliveryId) {
        Delivery delivery = deliveries.findById(deliveryId).orElseThrow();
        Instant now = Instant.now();
        for (int n = 1; n <= delivery.getMaxAttempts(); n++) {
            delivery.claim(WORKER, now, Duration.ofSeconds(15));
            delivery.recordRetryableFailure(now, now.plusSeconds(5L * n));
            attempts.save(attempt(deliveryId, n));
        }
        deliveries.save(delivery);
    }

    private void recordAttempts(UUID deliveryId, int count) {
        IntStream.rangeClosed(1, count).forEach(n -> attempts.save(attempt(deliveryId, n)));
    }

    private DeliveryAttempt attempt(UUID deliveryId, int attemptNumber) {
        Instant now = Instant.now();
        return DeliveryAttempt.record(
                deliveryId, attemptNumber, now, now.plusMillis(120),
                AttemptOutcome.RETRYABLE_FAILURE, 503, null, null,
                "service unavailable", null, now.plusSeconds(5), WORKER);
    }

    private static <T> T awaitResult(Future<T> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
