package com.caygnus.webhook.ingest.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param maxPayloadBytes rejected above this, before anything is persisted. The payload is stored
 *                        and forwarded verbatim, so an unbounded one would be an unbounded row
 *                        and an unbounded request to the receiver.
 */
@ConfigurationProperties(prefix = "webhook.ingest")
public record IngestProperties(int maxPayloadBytes) {
}
