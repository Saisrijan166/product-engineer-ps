package com.caygnus.webhook.receiver;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * The mode logic is the only part of the receiver anything depends on: {@code FAIL_N_THEN_OK}
 * miscounting by one would silently turn the retry scenario into a different test.
 */
class ReceiverStateTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-09-15T10:00:00Z"), ZoneOffset.UTC);
    private static final ReceiverProperties PROPERTIES =
            new ReceiverProperties(ReceiverMode.ALWAYS_OK, 200, 500);

    private final ReceiverState state = new ReceiverState(PROPERTIES, FIXED);

    @Test
    void startsInTheConfiguredDefaultMode() {
        assertThat(state.currentMode().mode()).isEqualTo(ReceiverMode.ALWAYS_OK);
        assertThat(receive().status()).isEqualTo(200);
    }

    @Test
    void alwaysFailDefaultsTo500() {
        state.applyMode(ReceiverMode.ALWAYS_FAIL, null, null, null, null);

        assertThat(statusesOf(3)).containsExactly(500, 500, 500);
    }

    @Test
    void failNThenOkFailsExactlyNTimes() {
        state.applyMode(ReceiverMode.FAIL_N_THEN_OK, 2, 503, null, null);

        assertThat(statusesOf(4)).containsExactly(503, 503, 200, 200);
    }

    @Test
    void settingTheModeRestartsTheFailureCountButKeepsHistory() {
        state.applyMode(ReceiverMode.FAIL_N_THEN_OK, 1, 503, null, null);
        receive();
        receive();

        state.applyMode(ReceiverMode.FAIL_N_THEN_OK, 1, 503, null, null);

        assertThat(statusesOf(2)).containsExactly(503, 200);
        assertThat(state.totalReceived()).isEqualTo(4);
    }

    @Test
    void aRetryAfterIsSentWithAFailureAndWithheldFromASuccess() {
        state.applyMode(ReceiverMode.FAIL_N_THEN_OK, 1, 503, null, 42);

        assertThat(receive().retryAfterSeconds()).isEqualTo(42);
        // The second request succeeds, and Retry-After alongside a 200 would be meaningless.
        assertThat(receive().retryAfterSeconds()).isNull();
    }

    @Test
    void noRetryAfterIsSentUnlessAScenarioAsksForOne() {
        state.applyMode(ReceiverMode.ALWAYS_FAIL, null, 500, null, null);

        assertThat(receive().retryAfterSeconds()).isNull();
    }

    @Test
    void timeoutModeDelaysByDefaultWithoutBeingAsked() {
        state.applyMode(ReceiverMode.TIMEOUT, null, null, null, null);

        assertThat(state.currentMode().delayMs()).isEqualTo(30_000L);
    }

    @Test
    void recordsHeadersAndBodyInArrivalOrder() {
        state.recordAndDecide("POST", "/receive", Map.of("x-idempotency-key", "d-1"), "{\"a\":1}");
        state.recordAndDecide("POST", "/receive", Map.of("x-idempotency-key", "d-1"), "{\"a\":1}");

        List<RecordedRequest> recorded = state.recorded();
        assertThat(recorded).extracting(RecordedRequest::sequence).containsExactly(1L, 2L);
        assertThat(recorded).allSatisfy(r -> {
            assertThat(r.headers()).containsEntry("x-idempotency-key", "d-1");
            assertThat(r.body()).isEqualTo("{\"a\":1}");
            assertThat(r.receivedAt()).isEqualTo(FIXED.instant());
        });
    }

    @Test
    void retainsOnlyTheMostRecentRequestsButKeepsCountingThemAll() {
        ReceiverState bounded = new ReceiverState(new ReceiverProperties(ReceiverMode.ALWAYS_OK, 200, 2), FIXED);

        IntStream.rangeClosed(1, 5).forEach(i -> bounded.recordAndDecide("POST", "/receive", Map.of(), "#" + i));

        assertThat(bounded.totalReceived()).isEqualTo(5);
        assertThat(bounded.recorded()).extracting(RecordedRequest::body).containsExactly("#4", "#5");
    }

    @Test
    void resetClearsHistoryAndReturnsToTheDefaultMode() {
        state.applyMode(ReceiverMode.ALWAYS_FAIL, null, null, null, null);
        receive();

        state.reset();

        assertThat(state.totalReceived()).isZero();
        assertThat(state.recorded()).isEmpty();
        assertThat(state.currentMode().mode()).isEqualTo(ReceiverMode.ALWAYS_OK);
    }

    @Test
    void countsEveryConcurrentRequestExactlyOnce() throws Exception {
        int concurrentRequests = 200;
        state.applyMode(ReceiverMode.FAIL_N_THEN_OK, 50, 503, null, null);

        List<Callable<ResponseDecision>> calls =
                IntStream.range(0, concurrentRequests).<Callable<ResponseDecision>>mapToObj(i -> this::receive).toList();

        List<ResponseDecision> decisions;
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            decisions = pool.invokeAll(calls).stream().map(ReceiverStateTest::get).toList();
        }

        assertThat(decisions).extracting(ResponseDecision::sequence)
                .doesNotHaveDuplicates()
                .hasSize(concurrentRequests);
        assertThat(decisions).filteredOn(d -> d.status() == 503).hasSize(50);
    }

    private ResponseDecision receive() {
        return state.recordAndDecide("POST", "/receive", Map.of(), "{}");
    }

    private List<Integer> statusesOf(int count) {
        return IntStream.range(0, count).mapToObj(i -> receive().status()).toList();
    }

    private static <T> T get(Future<T> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
