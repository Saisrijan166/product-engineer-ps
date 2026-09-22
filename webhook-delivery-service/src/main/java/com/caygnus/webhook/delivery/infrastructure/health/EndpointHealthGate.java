package com.caygnus.webhook.delivery.infrastructure.health;

import com.caygnus.webhook.common.model.AttemptOutcome;
import com.caygnus.webhook.delivery.config.EndpointHealthProperties;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stops hammering an endpoint that has stopped answering.
 *
 * <p>Without this, a receiver that is down still costs a full HTTP attempt per delivery per tick:
 * a worker occupied for the whole connect timeout, a connection from the pool, and a row in the
 * attempt history that says nothing the previous nine did not. The gate notices the pattern and
 * short-circuits, so the cost of an endpoint being down is bounded by how quickly we can decide
 * it is down rather than by how much traffic is aimed at it.
 *
 * <p><b>What counts as unhealthy.</b> Only retryable failures. A receiver answering {@code 422}
 * ten times is not unwell -- it is working perfectly and rejecting us, and opening the gate would
 * stop delivering to a healthy endpoint because our payloads are wrong. So a permanent failure
 * closes the gate just as a success does: both prove the endpoint is taking our traffic.
 *
 * <p><b>The per-instance limitation (ARCHITECTURE.md §10).</b> This state lives in one JVM's heap.
 * Run three delivery instances and you get three independent opinions about the same endpoint,
 * each needing its own ten failures to form one; restart an instance and its opinion is gone. In
 * the v1 design this was Redis, shared across replicas, and dropping Redis is what made it local
 * (§0). That is an accepted trade rather than an oversight, and it is acceptable here for three
 * reasons: the brief specifies a single configured endpoint, the attempt bound already caps the
 * damage at five calls per delivery, and the gate is an optimisation -- nothing about correctness
 * depends on it, so the worst case of a cold gate is a few extra attempts. Sharing it would mean
 * reintroducing a store, and with it a new thing that can be down.
 *
 * <p>Every method is {@code synchronized}. The lock is held for a handful of field reads with no
 * I/O behind it, and eight workers contending on that is nothing next to getting the transitions
 * subtly wrong under a half-open race.
 */
@Component
public class EndpointHealthGate {

    private static final Logger log = LoggerFactory.getLogger(EndpointHealthGate.class);

    private final Map<String, Endpoint> endpoints = new HashMap<>();
    private final int failureThreshold;
    private final Duration openFor;
    private final Clock clock;

    EndpointHealthGate(EndpointHealthProperties properties, Clock clock) {
        this.failureThreshold = properties.failureThreshold();
        this.openFor = properties.openFor();
        this.clock = clock;
    }

    /**
     * May we call this endpoint right now?
     *
     * <p>Once the open window has elapsed, exactly one caller is let through as a trial and the
     * rest keep waiting. Letting them all through would mean a receiver that is still down gets
     * the full burst it was being protected from, at the worst possible moment.
     */
    public synchronized boolean allow(String targetUrl) {
        Endpoint endpoint = endpoints.get(keyFor(targetUrl));
        if (endpoint == null || !endpoint.isOpen()) {
            return true;
        }

        Instant now = clock.instant();
        if (now.isBefore(endpoint.openUntil)) {
            return false;
        }
        if (endpoint.trialInFlight) {
            return false;
        }
        endpoint.trialInFlight = true;
        log.info("Endpoint {} has been quiet for {}; letting one attempt through to test it",
                keyFor(targetUrl), openFor);
        return true;
    }

    /**
     * Report what an attempt produced.
     *
     * <p>Only ever called for attempts that actually went out. A short-circuited attempt records
     * a retryable failure in the history -- it did fail -- but feeding that back here would have
     * the gate holding itself open on the strength of its own refusals.
     */
    public synchronized void recordOutcome(String targetUrl, AttemptOutcome outcome) {
        String key = keyFor(targetUrl);
        Endpoint endpoint = endpoints.computeIfAbsent(key, k -> new Endpoint());

        if (outcome == AttemptOutcome.RETRYABLE_FAILURE) {
            endpoint.consecutiveFailures++;
            endpoint.trialInFlight = false;
            if (endpoint.consecutiveFailures >= failureThreshold) {
                // Also covers a failed trial: it was already at or above the threshold, so this
                // simply starts the window again.
                boolean wasClosed = !endpoint.isOpen();
                endpoint.openUntil = clock.instant().plus(openFor);
                if (wasClosed) {
                    log.warn("Endpoint {} failed {} times in a row; holding off for {}",
                            key, endpoint.consecutiveFailures, openFor);
                }
            }
            return;
        }

        // A success, or a rejection the endpoint was healthy enough to send. Either way it is
        // taking our traffic.
        if (endpoint.isOpen()) {
            log.info("Endpoint {} answered again; resuming normal delivery", key);
        }
        endpoint.consecutiveFailures = 0;
        endpoint.openUntil = null;
        endpoint.trialInFlight = false;
    }

    /** For the metrics gauge in step 13, and for tests that need to know what it thinks. */
    public synchronized boolean isOpen(String targetUrl) {
        Endpoint endpoint = endpoints.get(keyFor(targetUrl));
        return endpoint != null && endpoint.isOpen();
    }

    public synchronized int consecutiveFailures(String targetUrl) {
        Endpoint endpoint = endpoints.get(keyFor(targetUrl));
        return endpoint == null ? 0 : endpoint.consecutiveFailures;
    }

    /**
     * Forget everything.
     *
     * <p>Exists for tests: the gate is the only load-bearing in-memory state in this service, and
     * Spring caches contexts across test classes, so without a way to clear it one class's failing
     * endpoint becomes another class's mystery.
     */
    public synchronized void reset() {
        endpoints.clear();
    }

    /**
     * Host and port, so two receivers on one machine are two endpoints. A target we cannot parse
     * is keyed by its raw text -- it will never succeed anyway, and throwing from the key would
     * turn a bad URL into a crashed worker.
     */
    private static String keyFor(String targetUrl) {
        try {
            URI uri = URI.create(targetUrl);
            return uri.getHost() == null ? targetUrl : uri.getAuthority();
        } catch (IllegalArgumentException malformed) {
            return targetUrl;
        }
    }

    private static final class Endpoint {

        private int consecutiveFailures;

        /** Non-null exactly while the gate is open or waiting to be tested. */
        private Instant openUntil;

        private boolean trialInFlight;

        boolean isOpen() {
            return openUntil != null;
        }
    }
}
