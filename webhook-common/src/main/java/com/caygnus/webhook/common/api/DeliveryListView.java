package com.caygnus.webhook.common.api;

import java.util.List;

/**
 * A page of deliveries.
 *
 * <p>Wrapped rather than returned as a bare array so the response has somewhere to grow -- and so
 * {@code count} makes it obvious when a caller has hit {@code limit} and is looking at a truncated
 * view of the dead-letter queue.
 */
public record DeliveryListView(List<DeliveryView> deliveries, int count) {

    public static DeliveryListView of(List<DeliveryView> deliveries) {
        return new DeliveryListView(deliveries, deliveries.size());
    }
}
