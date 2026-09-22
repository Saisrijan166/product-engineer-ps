package com.caygnus.webhook.delivery.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How often to look for due work, and how long a worker may hold it.
 *
 * @param enabled      false under the test profile, where tests tick the scheduler by name
 * @param pollInterval the floor on retry latency: a delivery due now waits at most this long
 * @param leaseSlack   added to the response timeout to give the lease. It must exceed the longest
 *                     an attempt can legitimately take, or the reaper would requeue deliveries
 *                     that are merely slow and the receiver would see avoidable duplicates.
 */
@ConfigurationProperties(prefix = "webhook.scheduler")
public record SchedulerProperties(boolean enabled, Duration pollInterval, Duration leaseSlack) {
}
