package com.caygnus.webhook.delivery.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The retry policy, as configuration. See ARCHITECTURE.md 9.2 for the profile values.
 *
 * @param maxAttempts  snapshotted onto each delivery at creation, so changing it never re-opens a
 *                     delivery that has already stopped
 * @param jitterFactor fraction either side of the computed delay; zero makes backoff deterministic,
 *                     which is how the test profile runs
 */
@ConfigurationProperties(prefix = "webhook.retry")
public record RetryProperties(
        int maxAttempts,
        Duration baseDelay,
        long multiplier,
        Duration maxDelay,
        double jitterFactor) {
}
