package com.caygnus.webhook.delivery.infrastructure.observability;

import com.caygnus.webhook.common.model.AttemptOutcome;
import com.caygnus.webhook.common.model.DeliveryStatus;
import com.caygnus.webhook.common.model.TerminalReason;
import com.caygnus.webhook.delivery.config.DeliveryProperties;
import com.caygnus.webhook.delivery.infrastructure.health.EndpointHealthGate;
import com.caygnus.webhook.delivery.infrastructure.persistence.DeliveryRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Every meter this service publishes, in one place.
 *
 * <p>Concentrated deliberately. Metric names are an interface -- dashboards and alerts are built
 * against them and break silently when they drift -- so having them scattered across the classes
 * that happen to emit them makes the contract impossible to review. Business code calls one
 * intent method; the names, tags and types live here.
 *
 * <p>The two gauges are the ones worth watching. {@code webhook_delivery_due_backlog} is the real
 * queue depth: deliveries past their {@code next_attempt_at} that no worker has picked up yet.
 * Because the queue is a table rather than a process, it survives restarts and is visible to
 * every instance, so a rising backlog means the fleet is genuinely behind rather than that one
 * box is sulking. {@code webhook_lease_reclaimed_total} climbing steadily is the clearest signal
 * available that instances are dying mid-attempt.
 */
@Component
public class DeliveryMetrics {

    private final MeterRegistry registry;
    private final Counter reclaimedLeases;

    DeliveryMetrics(
            MeterRegistry registry,
            DeliveryRepository deliveries,
            EndpointHealthGate healthGate,
            DeliveryProperties properties,
            Clock clock) {

        this.registry = registry;
        this.reclaimedLeases = Counter.builder("webhook.lease.reclaimed")
                .description("Deliveries taken back from a worker that stopped reporting")
                .register(registry);

        io.micrometer.core.instrument.Gauge.builder(
                        "webhook.delivery.due.backlog", () -> deliveries.countDue(clock.instant()))
                .description("Deliveries past their next attempt time and not yet claimed")
                .register(registry);

        io.micrometer.core.instrument.Gauge.builder(
                        "webhook.endpoint.gate.state", () -> healthGate.isOpen(properties.targetUrl()) ? 1 : 0)
                .description("1 while the engine is holding off calling the endpoint, 0 otherwise")
                .tag("endpoint", properties.targetUrl())
                .register(registry);
    }

    /**
     * One attempt finished.
     *
     * <p>The duration is tagged by outcome because the two distributions mean different things: a
     * slow success is a slow receiver, while a slow failure is almost always a timeout, and
     * averaging them together hides both.
     */
    public void attemptCompleted(AttemptOutcome outcome, Duration duration) {
        String outcomeTag = tagOf(outcome.name());
        Counter.builder("webhook.delivery.attempts")
                .description("Delivery attempts, by what they produced")
                .tag("outcome", outcomeTag)
                .register(registry)
                .increment();

        Timer.builder("webhook.delivery.duration")
                .description("How long an attempt took, from request to recorded outcome")
                .tag("outcome", outcomeTag)
                .register(registry)
                .record(duration);
    }

    /**
     * A delivery reached a state it will never leave.
     *
     * <p>Tagged by why, because the three mean very different things operationally: success is
     * the happy path, {@code non_retryable_response} points at the payload or the endpoint's
     * configuration, and {@code attempts_exhausted} points at a receiver that was down long
     * enough to burn the whole budget.
     */
    public void deliveryFinished(DeliveryStatus status, TerminalReason reason) {
        Counter.builder("webhook.delivery.terminal")
                .description("Deliveries that reached a terminal state, by reason")
                .tag("reason", reason == null ? tagOf(status.name()) : tagOf(reason.name()))
                .register(registry)
                .increment();
    }

    public void leaseReclaimed(int count) {
        reclaimedLeases.increment(count);
    }

    /** Prometheus convention: lower case, and no shouting. */
    private static String tagOf(String enumName) {
        return enumName.toLowerCase(Locale.ROOT);
    }
}
