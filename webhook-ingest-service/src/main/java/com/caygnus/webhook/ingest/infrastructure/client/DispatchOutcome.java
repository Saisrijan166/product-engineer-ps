package com.caygnus.webhook.ingest.infrastructure.client;

import java.util.UUID;

/**
 * What came back from one attempt to hand an event to the delivery service.
 *
 * <p>Three outcomes, and the distinction that matters is whether trying again could help. A 503
 * means the far end is momentarily unavailable and will be back; a 400 means we sent something it
 * will never accept, and nine more identical requests would only make the log longer.
 *
 * @param deliveryId set only when the delivery service acknowledged
 */
public record DispatchOutcome(UUID deliveryId, boolean accepted, boolean retryable, String error) {

    public static DispatchOutcome accepted(UUID deliveryId) {
        return new DispatchOutcome(deliveryId, true, false, null);
    }

    /** The far end is unwell, not wrong. */
    public static DispatchOutcome unavailable(String error) {
        return new DispatchOutcome(null, false, true, error);
    }

    /** The far end rejected the request itself. Retrying would produce the same rejection. */
    public static DispatchOutcome rejected(String error) {
        return new DispatchOutcome(null, false, false, error);
    }
}
