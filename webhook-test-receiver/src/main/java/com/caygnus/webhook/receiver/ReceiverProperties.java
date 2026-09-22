package com.caygnus.webhook.receiver;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Startup defaults, and the state {@code POST /control/reset} returns to.
 *
 * @param defaultMode         mode on startup and after a reset
 * @param defaultStatus       status for {@link ReceiverMode#ALWAYS_OK}
 * @param maxRecordedRequests how many requests to retain for inspection; the oldest are dropped
 *                            beyond this, so a long demo cannot grow the heap without bound
 */
@ConfigurationProperties(prefix = "receiver")
public record ReceiverProperties(ReceiverMode defaultMode, int defaultStatus, int maxRecordedRequests) {
}
