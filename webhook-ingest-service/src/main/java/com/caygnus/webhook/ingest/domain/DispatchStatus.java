package com.caygnus.webhook.ingest.domain;

/** Where an outbox row has got to on its way to the delivery service. */
public enum DispatchStatus {

    /** Written with the event, not yet acknowledged by the delivery service. */
    PENDING,

    /** The delivery service has a delivery for this event; {@code delivery_id} says which. */
    DISPATCHED,

    /** The dispatcher's own bounded retry ran out. Visible through the event's read API. */
    FAILED
}
