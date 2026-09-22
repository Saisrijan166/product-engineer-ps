package com.caygnus.webhook.delivery.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * When to stop calling an endpoint that has stopped answering.
 *
 * @param failureThreshold consecutive retryable failures before the gate opens. High enough that
 *                         a couple of timeouts during a deploy do not trip it, low enough that a
 *                         receiver which is genuinely down stops costing worker time quickly.
 * @param openFor          how long to hold off before letting one attempt through to test it.
 *                         Shorter than the third retry backoff on purpose, so a delivery still
 *                         gets a real attempt rather than exhausting against a closed gate.
 */
@ConfigurationProperties(prefix = "webhook.endpoint-health")
public record EndpointHealthProperties(int failureThreshold, Duration openFor) {
}
