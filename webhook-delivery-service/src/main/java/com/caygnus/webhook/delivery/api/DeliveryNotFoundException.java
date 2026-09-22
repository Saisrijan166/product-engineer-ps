package com.caygnus.webhook.delivery.api;

/** No delivery with that id. Distinct from "no delivery for that event", which is an empty list. */
public class DeliveryNotFoundException extends RuntimeException {

    public DeliveryNotFoundException(Object identifier) {
        super("No delivery found for " + identifier);
    }
}
