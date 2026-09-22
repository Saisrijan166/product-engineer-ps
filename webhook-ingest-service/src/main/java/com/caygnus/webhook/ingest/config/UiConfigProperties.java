package com.caygnus.webhook.ingest.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the demo UI should send its browser-side calls.
 *
 * <p>Separate from {@link DeliveryServiceProperties} because they answer different questions.
 * That one holds the address <em>this service</em> uses to reach the delivery service, which
 * inside Docker is {@code http://delivery-service:8081} -- a name that resolves on the compose
 * network and nowhere else. A browser on the host cannot use it. These are the addresses a
 * <em>browser</em> can reach, which is a genuinely different thing and has to be configured
 * separately rather than derived.
 *
 * <p>Demo UI only. Nothing in the delivery path reads these.
 */
@ConfigurationProperties(prefix = "webhook.ui")
public record UiConfigProperties(String deliveryBaseUrl, String receiverBaseUrl) {
}
