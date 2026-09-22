package com.caygnus.webhook.ingest.domain;

/**
 * The same event identifier arrived carrying a different payload.
 *
 * <p>Rejected rather than absorbed. A caller-supplied identifier is a promise that the event is
 * the same event; accepting a changed body under an id we have already accepted would mean either
 * silently ignoring the new content or silently replacing the old, and both hide a caller bug that
 * will be far harder to find once the original has been delivered.
 */
public class PayloadMismatchException extends RuntimeException {

    private final transient String eventId;

    public PayloadMismatchException(String eventId) {
        super(("Event '%s' was already accepted with a different payload. A stable event identifier "
                + "must denote a stable event.").formatted(eventId));
        this.eventId = eventId;
    }

    public String eventId() {
        return eventId;
    }
}
