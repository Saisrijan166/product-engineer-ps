package com.caygnus.webhook.common.api;

import java.net.URI;

/**
 * The {@code type} URIs used in RFC 7807 problem responses.
 *
 * <p>Stable identifiers, so a caller can branch on the problem without parsing prose. They are not
 * dereferenceable URLs and are not meant to be fetched -- RFC 7807 permits that, and inventing a
 * documentation site for a take-home would be pretending.
 */
public final class ProblemCodes {

    private static final String BASE = "urn:caygnus:webhook:problem:";

    /** The request body failed validation. */
    public static final URI VALIDATION_FAILED = URI.create(BASE + "validation-failed");

    /** No delivery exists for the given id or event id. */
    public static final URI DELIVERY_NOT_FOUND = URI.create(BASE + "delivery-not-found");

    /** The {@code Idempotency-Key} header disagreed with the {@code eventId} in the body. */
    public static final URI IDEMPOTENCY_KEY_MISMATCH = URI.create(BASE + "idempotency-key-mismatch");

    /** A query parameter was present but unusable. */
    public static final URI INVALID_QUERY = URI.create(BASE + "invalid-query");

    /** Another writer changed the delivery first; the request is safe to retry. */
    public static final URI CONCURRENT_MODIFICATION = URI.create(BASE + "concurrent-modification");

    /** Something the delivery's state machine does not permit. */
    public static final URI ILLEGAL_STATE_TRANSITION = URI.create(BASE + "illegal-state-transition");

    /** No event has been accepted under the given identifier. */
    public static final URI EVENT_NOT_FOUND = URI.create(BASE + "event-not-found");

    /** The same event identifier arrived carrying a different payload. */
    public static final URI PAYLOAD_MISMATCH = URI.create(BASE + "payload-mismatch");

    /** The store is unreachable; nothing was accepted and nothing was lost. */
    public static final URI STORAGE_UNAVAILABLE = URI.create(BASE + "storage-unavailable");

    /** The insert conflicted with a row that then vanished; the request is safe to retry. */
    public static final URI CONCURRENT_CREATION = URI.create(BASE + "concurrent-creation");

    /** Anything unclassified. */
    public static final URI INTERNAL_ERROR = URI.create(BASE + "internal-error");

    private ProblemCodes() {
    }
}
