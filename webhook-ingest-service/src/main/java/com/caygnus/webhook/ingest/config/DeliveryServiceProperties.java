package com.caygnus.webhook.ingest.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the delivery service lives, and how long to wait for it.
 *
 * <p>The timeouts matter more than they look. The dispatcher holds a database transaction across
 * this call, so an unbounded wait would be an unbounded transaction -- see {@code OutboxDispatcher}.
 */
@ConfigurationProperties(prefix = "webhook.delivery-service")
public record DeliveryServiceProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {
}
