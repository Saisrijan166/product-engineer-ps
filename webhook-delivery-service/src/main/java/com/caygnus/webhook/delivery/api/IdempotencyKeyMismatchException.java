package com.caygnus.webhook.delivery.api;

/**
 * The {@code Idempotency-Key} header and the body's {@code eventId} disagreed.
 *
 * <p>Rejected rather than resolved in the caller's favour. The two are meant to be the same value,
 * so a mismatch means the caller has confused two events -- and guessing which one it meant could
 * deliver the wrong payload, or deliver one event twice under two identities.
 */
public class IdempotencyKeyMismatchException extends RuntimeException {

    public IdempotencyKeyMismatchException(String headerKey, String bodyEventId) {
        super("Idempotency-Key '%s' does not match eventId '%s'".formatted(headerKey, bodyEventId));
    }
}
