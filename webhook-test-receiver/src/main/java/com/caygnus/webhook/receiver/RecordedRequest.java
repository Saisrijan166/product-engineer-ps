package com.caygnus.webhook.receiver;

import java.time.Instant;
import java.util.Map;

/**
 * One request as the receiver saw it, plus the status it answered with.
 *
 * <p>Headers are kept whole rather than filtered: the point of the demo is to show
 * {@code X-Idempotency-Key} staying identical across retries while {@code X-Webhook-Attempt}
 * climbs, and a filter would be one more thing to trust.
 *
 * @param sequence   1-based, in arrival order, and never reused within a run
 * @param respondedStatus what this receiver answered -- the delivery service's own view of the
 *                        attempt is recorded independently, so the two can be compared
 */
public record RecordedRequest(
        long sequence,
        Instant receivedAt,
        String method,
        String path,
        Map<String, String> headers,
        String body,
        int respondedStatus) {
}
