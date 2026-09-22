package com.caygnus.webhook.delivery.infrastructure.health;

import static com.caygnus.webhook.common.model.AttemptOutcome.PERMANENT_FAILURE;
import static com.caygnus.webhook.common.model.AttemptOutcome.RETRYABLE_FAILURE;
import static com.caygnus.webhook.common.model.AttemptOutcome.SUCCESS;
import static org.assertj.core.api.Assertions.assertThat;

import com.caygnus.webhook.common.model.AttemptOutcome;
import com.caygnus.webhook.delivery.config.EndpointHealthProperties;
import com.caygnus.webhook.delivery.support.MutableTestClock;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The gate's state machine, driven by a clock the test moves by hand.
 *
 * <p>Circuit breakers go wrong in quiet ways: one that never opens costs a little wasted work, one
 * that never closes stops delivering entirely and looks exactly like a receiver that is still
 * down. The transitions are cheap to get exactly right here, so they are pinned exactly --
 * including that it opens on the tenth failure and not the ninth.
 */
class EndpointHealthGateTest {

    private static final String ENDPOINT = "http://receiver.example:8082/receive";
    private static final Instant START = Instant.parse("2026-09-15T10:00:00Z");
    private static final int THRESHOLD = 10;
    private static final Duration OPEN_FOR = Duration.ofSeconds(30);

    private final MutableTestClock clock = new MutableTestClock(START);
    private EndpointHealthGate gate;

    @BeforeEach
    void setUp() {
        gate = new EndpointHealthGate(new EndpointHealthProperties(THRESHOLD, OPEN_FOR), clock);
    }

    // ------------------------------------------------------------- closed

    @Test
    void anUnknownEndpointIsAllowed() {
        assertThat(gate.allow(ENDPOINT)).isTrue();
        assertThat(gate.isOpen(ENDPOINT)).isFalse();
    }

    @Test
    void nineFailuresAreNotEnough() {
        fail(9);

        // Off by one here would open the gate a failure early on every deploy blip.
        assertThat(gate.isOpen(ENDPOINT)).isFalse();
        assertThat(gate.allow(ENDPOINT)).isTrue();
        assertThat(gate.consecutiveFailures(ENDPOINT)).isEqualTo(9);
    }

    @Test
    void aSuccessResetsTheRunOfFailures() {
        fail(9);
        gate.recordOutcome(ENDPOINT, SUCCESS);
        fail(9);

        // Consecutive means consecutive. Eighteen failures with a success in the middle is an
        // endpoint that is flaky, not one that is down.
        assertThat(gate.isOpen(ENDPOINT)).isFalse();
    }

    @Test
    void aPermanentRejectionCountsAsHealthy() {
        fail(9);
        gate.recordOutcome(ENDPOINT, PERMANENT_FAILURE);
        fail(9);

        // A receiver answering 422 is working perfectly and refusing us. Opening the gate would
        // stop delivering to a healthy endpoint because our payloads are wrong.
        assertThat(gate.isOpen(ENDPOINT)).isFalse();
        assertThat(gate.consecutiveFailures(ENDPOINT)).isEqualTo(9);
    }

    @Test
    void permanentRejectionsAloneNeverOpenTheGate() {
        IntStream.range(0, 50).forEach(i -> gate.recordOutcome(ENDPOINT, PERMANENT_FAILURE));

        assertThat(gate.isOpen(ENDPOINT)).isFalse();
        assertThat(gate.allow(ENDPOINT)).isTrue();
    }

    // --------------------------------------------------------------- open

    @Test
    void theTenthConsecutiveFailureOpensTheGate() {
        fail(THRESHOLD);

        assertThat(gate.isOpen(ENDPOINT)).isTrue();
        assertThat(gate.allow(ENDPOINT)).isFalse();
    }

    @Test
    void nothingGetsThroughWhileTheWindowIsRunning() {
        fail(THRESHOLD);

        clock.advance(OPEN_FOR.minusSeconds(1));

        assertThat(gate.allow(ENDPOINT)).isFalse();
        assertThat(gate.allow(ENDPOINT)).isFalse();
    }

    // ---------------------------------------------------------- half-open

    @Test
    void afterTheWindowExactlyOneAttemptIsLetThrough() {
        fail(THRESHOLD);
        clock.advance(OPEN_FOR);

        assertThat(gate.allow(ENDPOINT)).as("the trial").isTrue();
        // The rest keep waiting. Letting them all through would hand a receiver that is still
        // down the exact burst it was being protected from.
        assertThat(gate.allow(ENDPOINT)).as("everyone else").isFalse();
        assertThat(gate.allow(ENDPOINT)).isFalse();
    }

    @Test
    void aSuccessfulTrialClosesTheGate() {
        fail(THRESHOLD);
        clock.advance(OPEN_FOR);
        gate.allow(ENDPOINT);

        gate.recordOutcome(ENDPOINT, SUCCESS);

        assertThat(gate.isOpen(ENDPOINT)).isFalse();
        assertThat(gate.allow(ENDPOINT)).isTrue();
        assertThat(gate.allow(ENDPOINT)).isTrue();
        assertThat(gate.consecutiveFailures(ENDPOINT)).isZero();
    }

    @Test
    void aTrialThatIsRejectedAlsoClosesTheGate() {
        fail(THRESHOLD);
        clock.advance(OPEN_FOR);
        gate.allow(ENDPOINT);

        // The endpoint answered, which is the question the trial was asking.
        gate.recordOutcome(ENDPOINT, PERMANENT_FAILURE);

        assertThat(gate.isOpen(ENDPOINT)).isFalse();
    }

    @Test
    void aFailedTrialStartsTheWindowAgain() {
        fail(THRESHOLD);
        clock.advance(OPEN_FOR);
        gate.allow(ENDPOINT);

        gate.recordOutcome(ENDPOINT, RETRYABLE_FAILURE);

        assertThat(gate.isOpen(ENDPOINT)).isTrue();
        assertThat(gate.allow(ENDPOINT)).isFalse();

        // ...and the new window is a full one, measured from the failed trial rather than from
        // when the gate first opened.
        clock.advance(OPEN_FOR.minusSeconds(1));
        assertThat(gate.allow(ENDPOINT)).isFalse();
        clock.advance(Duration.ofSeconds(1));
        assertThat(gate.allow(ENDPOINT)).isTrue();
    }

    @Test
    void aReceiverThatStaysDownIsTestedOnceEveryWindowAndNoMore() {
        fail(THRESHOLD);

        for (int window = 0; window < 5; window++) {
            clock.advance(OPEN_FOR);
            assertThat(gate.allow(ENDPOINT)).as("trial in window %d", window).isTrue();
            assertThat(gate.allow(ENDPOINT)).as("no second trial in window %d", window).isFalse();
            gate.recordOutcome(ENDPOINT, RETRYABLE_FAILURE);
        }

        assertThat(gate.isOpen(ENDPOINT)).isTrue();
    }

    // ------------------------------------------------------- bookkeeping

    @Test
    void endpointsAreJudgedIndependently() {
        String other = "http://other.example:9000/receive";
        fail(THRESHOLD);

        assertThat(gate.allow(ENDPOINT)).isFalse();
        // One broken receiver must not stop deliveries to a different one.
        assertThat(gate.allow(other)).isTrue();
        assertThat(gate.isOpen(other)).isFalse();
    }

    @Test
    void theSameHostOnDifferentPortsIsTwoEndpoints() {
        fail(THRESHOLD);

        assertThat(gate.allow("http://receiver.example:9999/receive")).isTrue();
    }

    @Test
    void differentPathsOnOneEndpointShareItsHealth() {
        fail(THRESHOLD);

        // It is the host that is down, not the path.
        assertThat(gate.allow("http://receiver.example:8082/somewhere/else")).isFalse();
    }

    @Test
    void anUnparseableTargetIsHandledRatherThanThrown() {
        IntStream.range(0, THRESHOLD).forEach(i -> gate.recordOutcome("not-a-url", RETRYABLE_FAILURE));

        assertThat(gate.allow("not-a-url")).isFalse();
        assertThat(gate.allow(ENDPOINT)).isTrue();
    }

    @Test
    void resetForgetsEverything() {
        fail(THRESHOLD);
        assertThat(gate.allow(ENDPOINT)).isFalse();

        gate.reset();

        assertThat(gate.allow(ENDPOINT)).isTrue();
        assertThat(gate.consecutiveFailures(ENDPOINT)).isZero();
    }

    private void fail(int times) {
        record(RETRYABLE_FAILURE, times);
    }

    private void record(AttemptOutcome outcome, int times) {
        IntStream.range(0, times).forEach(i -> gate.recordOutcome(ENDPOINT, outcome));
    }
}
