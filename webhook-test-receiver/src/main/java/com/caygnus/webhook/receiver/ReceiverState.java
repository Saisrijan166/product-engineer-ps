package com.caygnus.webhook.receiver;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * All of the receiver's state, and the only place it is mutated.
 *
 * <p>Every method is {@code synchronized}: the delivery service runs several workers, so requests
 * arrive concurrently, and a counter like {@code FAIL_N_THEN_OK} is only meaningful if increments
 * and reads happen under one lock. The lock is held for microseconds because
 * {@link #recordAndDecide} decides but never waits -- the caller does the waiting.
 */
@Component
public class ReceiverState {

    private static final int DEFAULT_FAIL_COUNT = 1;
    private static final int DEFAULT_FAILURE_STATUS = 500;
    private static final long DEFAULT_TIMEOUT_DELAY_MS = 30_000L;

    private final ReceiverProperties properties;
    private final Clock clock;

    private final Deque<RecordedRequest> recorded = new ArrayDeque<>();

    private ReceiverMode mode;
    private int failCount;
    private int status;
    private long delayMs;

    /** Sent as a Retry-After header on a failure, when a scenario wants to dictate the backoff. */
    private Integer retryAfterSeconds;

    /** Total ever received; unaffected by trimming, so counts stay assertable after a long run. */
    private long totalReceived;

    /** Reset whenever the mode is set, so {@code FAIL_N_THEN_OK} counts from that moment. */
    private long servedSinceModeSet;

    ReceiverState(ReceiverProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        reset();
    }

    /**
     * Record one request and decide the answer, in a single atomic step.
     *
     * @return the status to send and how long to wait first
     */
    public synchronized ResponseDecision recordAndDecide(
            String method, String path, Map<String, String> headers, String body) {

        servedSinceModeSet++;
        totalReceived++;

        int responseStatus = switch (mode) {
            case ALWAYS_OK, ALWAYS_FAIL, TIMEOUT -> status;
            case FAIL_N_THEN_OK -> servedSinceModeSet <= failCount ? status : 200;
        };

        recorded.addLast(new RecordedRequest(
                totalReceived, Instant.now(clock), method, path, headers, body, responseStatus));
        while (recorded.size() > properties.maxRecordedRequests()) {
            recorded.removeFirst();
        }

        // Retry-After only makes sense alongside a failure; sending it with a 200 would be noise.
        Integer retryAfter = responseStatus >= 400 ? retryAfterSeconds : null;
        return new ResponseDecision(totalReceived, responseStatus, delayMs, retryAfter);
    }

    /**
     * Switch mode. History is deliberately kept -- a scenario often sets a mode, drives traffic,
     * then switches to prove recovery, and the attempts before the switch are the interesting part.
     * Only the {@code FAIL_N_THEN_OK} counter restarts.
     *
     * @param requestedFailCount how many to fail; null means 1
     * @param requestedStatus    the failing (or fixed) status; null means 200 for
     *                           {@link ReceiverMode#ALWAYS_OK} and {@link ReceiverMode#TIMEOUT},
     *                           500 otherwise
     * @param requestedDelayMs   wait before answering; null means 30s for
     *                           {@link ReceiverMode#TIMEOUT} and no wait otherwise
     * @param requestedRetryAfterSeconds send this Retry-After on failures; null sends none
     */
    public synchronized void applyMode(
            ReceiverMode requestedMode,
            Integer requestedFailCount,
            Integer requestedStatus,
            Long requestedDelayMs,
            Integer requestedRetryAfterSeconds) {

        this.mode = requestedMode;
        this.failCount = requestedFailCount != null ? requestedFailCount : DEFAULT_FAIL_COUNT;
        this.status = requestedStatus != null ? requestedStatus : defaultStatusFor(requestedMode);
        this.delayMs = requestedDelayMs != null
                ? requestedDelayMs
                : (requestedMode == ReceiverMode.TIMEOUT ? DEFAULT_TIMEOUT_DELAY_MS : 0L);
        this.retryAfterSeconds = requestedRetryAfterSeconds;
        this.servedSinceModeSet = 0;
    }

    /** Back to startup state, history included. Called between test classes. */
    public synchronized void reset() {
        recorded.clear();
        totalReceived = 0;
        applyMode(properties.defaultMode(), null, properties.defaultStatus(), null, null);
    }

    public synchronized List<RecordedRequest> recorded() {
        return List.copyOf(recorded);
    }

    public synchronized long totalReceived() {
        return totalReceived;
    }

    public synchronized ModeView currentMode() {
        return new ModeView(mode, failCount, status, delayMs, retryAfterSeconds, servedSinceModeSet);
    }

    private int defaultStatusFor(ReceiverMode requestedMode) {
        return switch (requestedMode) {
            case ALWAYS_OK, TIMEOUT -> properties.defaultStatus();
            case ALWAYS_FAIL, FAIL_N_THEN_OK -> DEFAULT_FAILURE_STATUS;
        };
    }

    /** The effective mode, echoed back so a caller can see the defaults that were filled in. */
    public record ModeView(
            ReceiverMode mode,
            int failCount,
            int status,
            long delayMs,
            Integer retryAfterSeconds,
            long servedSinceModeSet) {
    }
}
