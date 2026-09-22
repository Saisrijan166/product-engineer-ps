package com.caygnus.webhook.delivery.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Backoff is asserted, never waited for: the clock is fixed and the randomness is seeded, so every
 * case here is a pure computation that either holds or does not.
 */
class BackoffPolicyTest {

    private static final Instant NOW = Instant.parse("2026-09-15T10:00:00Z");
    private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);

    /** The default profile: 5s, 25s, 125s, then capped at 10m. */
    private static BackoffPolicy defaultProfile(double jitterFactor) {
        return new BackoffPolicy(
                Duration.ofSeconds(5), 5, Duration.ofMinutes(10), jitterFactor, FIXED, new Random(42));
    }

    private static BackoffPolicy withoutJitter() {
        return defaultProfile(0);
    }

    @ParameterizedTest(name = "attempt {0} -> {1}s")
    @CsvSource({"1, 5", "2, 25", "3, 125", "4, 600", "5, 600", "9, 600"})
    void growsGeometricallyThenHoldsAtTheCap(int attemptNumber, long expectedSeconds) {
        assertThat(withoutJitter().exponentialDelay(attemptNumber))
                .isEqualTo(Duration.ofSeconds(expectedSeconds));
    }

    @Test
    void theDemoProfileFitsInsideAShortVideo() {
        BackoffPolicy demo = new BackoffPolicy(
                Duration.ofSeconds(1), 2, Duration.ofSeconds(30), 0, FIXED, new Random(42));

        List<Duration> schedule = IntStream.rangeClosed(1, 4).mapToObj(demo::exponentialDelay).toList();

        assertThat(schedule).containsExactly(
                Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ofSeconds(8));
        assertThat(schedule.stream().reduce(Duration.ZERO, Duration::plus)).isEqualTo(Duration.ofSeconds(15));
    }

    @Test
    void theTestProfileIsImmediate() {
        BackoffPolicy test = new BackoffPolicy(Duration.ZERO, 1, Duration.ZERO, 0, FIXED, new Random(42));

        assertThat(test.nextDelay(1, null)).isZero();
        assertThat(test.nextDelay(5, null)).isZero();
        assertThat(test.nextAttemptAt(3, null)).isEqualTo(NOW);
    }

    @Test
    void jitterStaysInsideThePlusOrMinusTwentyPercentBand() {
        BackoffPolicy jittered = defaultProfile(0.2);
        Duration base = Duration.ofSeconds(25);

        List<Duration> delays = IntStream.range(0, 1_000).mapToObj(i -> jittered.nextDelay(2, null)).toList();

        assertThat(delays).allSatisfy(delay -> assertThat(delay)
                .isBetween(multiply(base, 0.8), multiply(base, 1.2)));
    }

    @Test
    void jitterActuallySpreadsRatherThanReturningTheSameNumber() {
        BackoffPolicy jittered = defaultProfile(0.2);

        // Why jitter exists: a receiver coming back from an outage must not be met by every
        // waiting delivery in the same millisecond.
        assertThat(IntStream.range(0, 100).mapToObj(i -> jittered.nextDelay(2, null)).distinct().count())
                .isGreaterThan(50L);
    }

    @Test
    void aSeededRandomMakesTheSpreadReproducible() {
        List<Duration> first = IntStream.range(0, 20).mapToObj(i -> defaultProfile(0.2).nextDelay(2, null)).toList();
        List<Duration> second = IntStream.range(0, 20).mapToObj(i -> defaultProfile(0.2).nextDelay(2, null)).toList();

        assertThat(first).isEqualTo(second);
    }

    @Test
    void nextAttemptAtIsTheDelayFromTheInjectedClock() {
        assertThat(withoutJitter().nextAttemptAt(2, null)).isEqualTo(NOW.plusSeconds(25));
    }

    @Test
    void retryAfterOverridesTheComputedDelayExactly() {
        // Not jittered: the receiver named a time, and second-guessing it defeats the point.
        assertThat(withoutJitter().nextDelay(1, 90)).isEqualTo(Duration.ofSeconds(90));
        assertThat(defaultProfile(0.2).nextDelay(1, 90)).isEqualTo(Duration.ofSeconds(90));
    }

    @Test
    void retryAfterIsClampedUpToOneSecond() {
        assertThat(withoutJitter().nextDelay(1, 0)).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void retryAfterIsClampedDownToTheMaximumDelay() {
        // A header is not permission to schedule an attempt an hour out.
        assertThat(withoutJitter().nextDelay(1, 3_600)).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void theCeilingWinsWhenTheConfiguredMaximumIsBelowTheFloor() {
        BackoffPolicy test = new BackoffPolicy(Duration.ZERO, 1, Duration.ZERO, 0, FIXED, new Random(42));

        assertThat(test.nextDelay(1, 30)).isZero();
    }

    @Test
    void anAbsentOrNonsensicalRetryAfterFallsBackToExponential() {
        assertThat(withoutJitter().nextDelay(2, null)).isEqualTo(Duration.ofSeconds(25));
        assertThat(withoutJitter().nextDelay(2, -5)).isEqualTo(Duration.ofSeconds(25));
    }

    @Test
    void rejectsAnAttemptNumberBelowOne() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> withoutJitter().exponentialDelay(0))
                .withMessageContaining("1-based");
    }

    @Test
    void rejectsAConfigurationThatCouldShrinkOrGoNegative() {
        assertThatIllegalArgumentException().isThrownBy(() -> new BackoffPolicy(
                Duration.ofSeconds(-1), 5, Duration.ofMinutes(10), 0.2, FIXED, new Random()));
        assertThatIllegalArgumentException().isThrownBy(() -> new BackoffPolicy(
                Duration.ofSeconds(5), 0, Duration.ofMinutes(10), 0.2, FIXED, new Random()));
        assertThatIllegalArgumentException().isThrownBy(() -> new BackoffPolicy(
                Duration.ofSeconds(5), 5, Duration.ofMinutes(10), 1.0, FIXED, new Random()));
    }

    private static Duration multiply(Duration duration, double factor) {
        return Duration.ofNanos((long) (duration.toNanos() * factor));
    }
}
