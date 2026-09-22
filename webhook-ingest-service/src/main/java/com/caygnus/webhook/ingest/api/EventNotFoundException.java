package com.caygnus.webhook.ingest.api;

/** No event has been accepted under that identifier. */
public class EventNotFoundException extends RuntimeException {

    private final transient String eventId;

    public EventNotFoundException(String eventId) {
        super("No event has been accepted with id '%s'".formatted(eventId));
        this.eventId = eventId;
    }

    public String eventId() {
        return eventId;
    }
}
