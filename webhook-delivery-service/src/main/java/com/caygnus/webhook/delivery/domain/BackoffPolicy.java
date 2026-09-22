package com.caygnus.webhook.delivery.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Random;

/**
 * When to try again: {@code min(base * multiplier^(n-1), max)}, spread by jitter, unless the
 * receiver told us itself.
 *
 * <p>Jitter matters more here than it would behind a queue. Every instance polls the same index on
 * the same column, so without it a receiver coming back from an outage is met by every waiting
 * delivery at once -- which is often what knocks it over again.
 *
 * <p>Both collaborators are injected for the same reason: this class has to be provable. The
 * {@link Clock} means a test can ask what would happen in ten minutes without waiting, and the
 * seeded {@link Random} means the spread can be asserted rather than hoped for.
 */
public final class BackoffPolicy {

    /** A receiver asking us back sooner than this is ignored; it is almost always a header bug. */
    private static final Duration MIN_RETRY_AFTER = Duration.ofSeconds(1);

    private final Duration baseDelay;
    private final long multiplier;
    private final Duration maxDelay;
    private final double jitterFactor;
    private final Clock clock;
    private final Random random;

    /**
     * @param jitterFactor fraction either side of the computed delay, so 0.2 means +/-20%. Zero
     *                     makes the policy deterministic, which is how the test profile runs it.
     */
    public BackoffPolicy(
            Duration baseDelay, long multiplier, Duration maxDelay, double jitterFactor, Clock clock, Random random) {

        if (baseDelay.isNegative() || maxDelay.isNegative()) {
            throw new IllegalArgumentException("delays must not be negative");
        }
        if (multiplier < 1) {
            throw new IllegalArgumentException("multiplier must be at least 1, so backoff never shrinks");
        }
        if (jitterFactor < 0 || jitterFactor >= 1) {
            throw new IllegalArgumentException("jitterFactor must be in [0, 1), so a delay is never negative");
        }
        this.baseDelay = baseDelay;
        this.multiplier = multiplier;
        this.maxDelay = maxDelay;
        this.jitterFactor = jitterFactor;
        this.clock = clock;
        this.random = random;
    }

    /**
     * When the next attempt becomes due.
     *
     * @param attemptNumber   the attempt that just failed, 1-based
     * @param retryAfterSeconds the receiver's {@code Retry-After}, or null when it did not send one
     */
    public Instant nextAttemptAt(int attemptNumber, Integer retryAfterSeconds) {
        return clock.instant().plus(nextDelay(attemptNumber, retryAfterSeconds));
    }

    /**
     * The delay before the next attempt.
     *
     * <p>A usable {@code Retry-After} wins outright and is <em>not</em> jittered: it is the
     * receiver naming a time, and second-guessing it by up to 20% would defeat the point of asking.
     * It is still clamped, because a header is not permission to schedule an attempt an hour out.
     */
    public Duration nextDelay(int attemptNumber, Integer retryAfterSeconds) {
        Duration retryAfter = usableRetryAfter(retryAfterSeconds);
        return retryAfter != null ? clamp(retryAfter) : jitter(exponentialDelay(attemptNumber));
    }

    /** The unjittered, uncapped-by-header delay. Exposed for the tests and for logging. */
    public Duration exponentialDelay(int attemptNumber) {
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber is 1-based, got " + attemptNumber);
        }
        Duration delay = baseDelay;
        // Multiply rather than pow: it cannot overflow, because it stops as soon as the cap is
        // reached. With five attempts that is at most a handful of iterations either way.
        for (int i = 1; i < attemptNumber && delay.compareTo(maxDelay) < 0; i++) {
            delay = delay.multipliedBy(multiplier);
        }
        return delay.compareTo(maxDelay) > 0 ? maxDelay : delay;
    }

    private Duration usableRetryAfter(Integer retryAfterSeconds) {
        if (retryAfterSeconds == null || retryAfterSeconds < 0) {
            return null;
        }
        return Duration.ofSeconds(retryAfterSeconds);
    }

    /**
     * Floor then ceiling, in that order, so the ceiling always wins. That ordering is what keeps a
     * configuration like the test profile's -- where {@code maxDelay} is below the one-second floor
     * -- coherent instead of contradictory.
     */
    private Duration clamp(Duration retryAfter) {
        Duration floored = retryAfter.compareTo(MIN_RETRY_AFTER) < 0 ? MIN_RETRY_AFTER : retryAfter;
        return floored.compareTo(maxDelay) > 0 ? maxDelay : floored;
    }

    private Duration jitter(Duration delay) {
        if (jitterFactor == 0 || delay.isZero()) {
            return delay;
        }
        double factor = 1.0 + jitterFactor * (2 * random.nextDouble() - 1.0);
        return Duration.ofNanos((long) (delay.toNanos() * factor));
    }
}
