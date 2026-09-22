package com.caygnus.webhook.delivery.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * A clock a test moves by hand.
 *
 * <p>This is what lets the suite prove retry timing without spending it. A test that wants to know
 * what happens twenty-five seconds into a backoff says so, and the next assertion runs immediately.
 * Nothing sleeps, so nothing is flaky on a loaded machine, and the whole suite finishes in seconds
 * rather than in the wall-clock time the retry schedule describes.
 *
 * <p>Milliseconds, not nanoseconds: PostgreSQL stores microseconds, so a nanosecond-precision
 * instant would not survive a round trip and assertions would end up being about rounding.
 */
public final class MutableTestClock extends Clock {

    private final ZoneId zone;
    private volatile Instant instant;

    public MutableTestClock(Instant start) {
        this(start, ZoneOffset.UTC);
    }

    private MutableTestClock(Instant start, ZoneId zone) {
        this.instant = start.truncatedTo(ChronoUnit.MILLIS);
        this.zone = zone;
    }

    /** Move time forward. Never backwards: nothing in the system is prepared for that. */
    public void advance(Duration amount) {
        if (amount.isNegative()) {
            throw new IllegalArgumentException("Time does not go backwards, got " + amount);
        }
        instant = instant.plus(amount).truncatedTo(ChronoUnit.MILLIS);
    }

    public void set(Instant newInstant) {
        this.instant = newInstant.truncatedTo(ChronoUnit.MILLIS);
    }

    @Override
    public Instant instant() {
        return instant;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new MutableTestClock(instant, newZone);
    }

    @Override
    public String toString() {
        return "MutableTestClock[" + instant + "]";
    }
}
