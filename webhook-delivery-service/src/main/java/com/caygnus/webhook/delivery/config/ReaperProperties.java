package com.caygnus.webhook.delivery.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How often to look for work abandoned by a worker that stopped reporting.
 *
 * @param enabled  false under the test profile, where tests run the reaper by name
 * @param interval the upper bound on how long a crashed attempt stays invisible. Recovery latency,
 *                 not throughput -- sweeping costs one indexed query against a partial index that
 *                 only ever contains in-flight rows.
 * @param batchSize how many to reclaim per pass, so one sweep after a mass restart cannot hold a
 *                  transaction open over thousands of rows
 */
@ConfigurationProperties(prefix = "webhook.reaper")
public record ReaperProperties(boolean enabled, Duration interval, int batchSize) {
}
