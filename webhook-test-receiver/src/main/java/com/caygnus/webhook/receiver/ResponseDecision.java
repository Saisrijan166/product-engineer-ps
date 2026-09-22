package com.caygnus.webhook.receiver;

/**
 * What {@link ReceiverState} decided for one request, handed back so the controller can wait
 * outside the lock: a slow answer must not hold up concurrent requests.
 */
public record ResponseDecision(long sequence, int status, long delayMs, Integer retryAfterSeconds) {
}
