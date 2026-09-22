package com.caygnus.webhook.ingest.infrastructure.client;

import com.caygnus.webhook.common.api.DeliveryView;

/**
 * The result of asking the delivery service what became of an event.
 *
 * <p>Three outcomes, and the middle one is the reason this is not an {@code Optional}. "There is
 * no delivery yet" and "I could not find out" look identical to a caller holding an empty result,
 * and they mean opposite things: the first is the ordinary state of an event whose dispatch is
 * still pending, the second is a degraded read that should not be mistaken for an answer.
 *
 * @param delivery null unless the delivery service answered and had one
 * @param error    null unless the lookup itself failed
 */
public record DeliveryLookup(DeliveryView delivery, String error) {

    public static DeliveryLookup found(DeliveryView delivery) {
        return new DeliveryLookup(delivery, null);
    }

    /** The delivery service answered, and has nothing for this event yet. Not an error. */
    public static DeliveryLookup absent() {
        return new DeliveryLookup(null, null);
    }

    /** The delivery service could not be asked. The event's own data is still good. */
    public static DeliveryLookup unavailable(String error) {
        return new DeliveryLookup(null, error);
    }

    public boolean failed() {
        return error != null;
    }
}
