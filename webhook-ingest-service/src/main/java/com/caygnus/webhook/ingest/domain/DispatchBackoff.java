package com.caygnus.webhook.ingest.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * When to try the delivery service again: {@code min(base * multiplier^(n-1), max)}.
 *
 * <p>Deliberately a separate, simpler thing from the delivery service's {@code BackoffPolicy}
 * rather than a shared one. They look alike today and are not the same policy: that one governs
 * how hard we try a customer's receiver and is part of the documented contract, this one governs
 * how hard one of our services tries to reach another. Sharing them would mean a change to the
 * published retry policy silently altering internal plumbing, or the reverse.
 *
 * <p>No jitter, for the same reason. Jitter exists to stop a recovering receiver being hit by
 * every waiting delivery at once; here the rows are already spread by {@code SKIP LOCKED} across
 * whichever instances claim them, and a synchronised burst of at most a batch of internal calls
 * is not a thundering herd.
 */
public final class DispatchBackoff {

    private final Duration baseDelay;
    private final long multiplier;
    private final Duration maxDelay;
    private final Clock clock;

    public DispatchBackoff(Duration baseDelay, long multiplier, Duration maxDelay, Clock clock) {
        if (baseDelay.isNegative() || maxDelay.isNegative()) {
            throw new IllegalArgumentException("delays must not be negative");
        }
        if (multiplier < 1) {
            throw new IllegalArgumentException("multiplier must be at least 1, so backoff never shrinks");
        }
        this.baseDelay = baseDelay;
        this.multiplier = multiplier;
        this.maxDelay = maxDelay;
        this.clock = clock;
    }

    /** @param failedAttempts how many attempts have now failed, 1-based */
    public Instant nextDispatchAt(int failedAttempts) {
        return clock.instant().plus(delayAfter(failedAttempts));
    }

    public Duration delayAfter(int failedAttempts) {
        if (failedAttempts < 1) {
            throw new IllegalArgumentException("failedAttempts is 1-based, got " + failedAttempts);
        }
        Duration delay = baseDelay;
        // Multiply rather than pow: it stops at the cap, so it cannot overflow.
        for (int i = 1; i < failedAttempts && delay.compareTo(maxDelay) < 0; i++) {
            delay = delay.multipliedBy(multiplier);
        }
        return delay.compareTo(maxDelay) > 0 ? maxDelay : delay;
    }
}
