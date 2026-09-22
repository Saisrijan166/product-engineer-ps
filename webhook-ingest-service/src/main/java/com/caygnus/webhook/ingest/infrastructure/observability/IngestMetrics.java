package com.caygnus.webhook.ingest.infrastructure.observability;

import com.caygnus.webhook.ingest.infrastructure.persistence.OutboxDispatchRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Every meter this service publishes, in one place -- see {@code DeliveryMetrics} for why they
 * are concentrated rather than left where they are emitted.
 *
 * <p>{@code webhook_outbox_pending} is the one to alert on, and the alert should be on its *age*
 * rather than its size. A handful of pending rows is a dispatcher that has not ticked yet; the
 * same handful still pending a minute later means the delivery service is unreachable and events
 * are piling up accepted-but-undelivered. That is the failure this service is built to survive,
 * and also the one nobody notices without a gauge, because ingestion carries on returning 201.
 *
 * <p>{@code webhook_ingest_duplicates_total} is the AC4 evidence as a number. A sudden climb
 * usually means a producer has started retrying -- worth knowing, and entirely harmless.
 */
@Component
public class IngestMetrics {

    private final Counter duplicateSubmissions;

    IngestMetrics(MeterRegistry registry, OutboxDispatchRepository outbox) {
        this.duplicateSubmissions = Counter.builder("webhook.ingest.duplicates")
                .description("Submissions of an event identifier that had already been accepted")
                .register(registry);

        Gauge.builder("webhook.outbox.pending", outbox::countPending)
                .description("Dispatch intents not yet acknowledged by the delivery service")
                .register(registry);
    }

    public void duplicateSubmission() {
        duplicateSubmissions.increment();
    }
}
