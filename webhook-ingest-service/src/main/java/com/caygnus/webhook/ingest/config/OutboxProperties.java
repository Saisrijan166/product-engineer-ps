package com.caygnus.webhook.ingest.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The dispatcher's own retry policy, which is not the delivery attempt budget.
 *
 * <p>Two different failures with two different audiences. The delivery budget governs how hard we
 * try a customer's receiver, and it is part of the documented contract. This governs how hard one
 * of our services tries to reach another, and a caller never sees it -- so it is more patient and
 * more forgiving, because the far end coming back is entirely within our control.
 *
 * @param enabled     false under the test profile, where tests tick the dispatcher by name
 * @param batchSize   how many rows one tick may hold locked; see {@code OutboxDispatcher} for why
 *                    this number is also a bound on how long a transaction stays open
 * @param maxAttempts after this many failures the row is parked as FAILED with its last error,
 *                    rather than retried forever against an endpoint that is clearly not coming
 *                    back on its own
 */
@ConfigurationProperties(prefix = "webhook.outbox")
public record OutboxProperties(
        boolean enabled,
        Duration pollInterval,
        int batchSize,
        int maxAttempts,
        Duration baseDelay,
        long multiplier,
        Duration maxDelay) {
}
