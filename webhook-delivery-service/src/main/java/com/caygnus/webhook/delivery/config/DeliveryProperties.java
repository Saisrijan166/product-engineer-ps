package com.caygnus.webhook.delivery.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The one configurable webhook endpoint and how patiently to wait for it.
 *
 * @param targetUrl              resolved once per delivery at creation and stored on the row, so
 *                               the history stays truthful if this changes later
 * @param maxConcurrentAttempts  the executor bound, which is also the claim batch size -- unclaimed
 *                               work waits in PostgreSQL rather than in memory
 */
@ConfigurationProperties(prefix = "webhook.delivery")
public record DeliveryProperties(
        String targetUrl,
        Duration connectTimeout,
        Duration responseTimeout,
        int maxConcurrentAttempts) {
}
