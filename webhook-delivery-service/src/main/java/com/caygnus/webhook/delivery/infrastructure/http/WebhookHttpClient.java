package com.caygnus.webhook.delivery.infrastructure.http;

import com.caygnus.webhook.common.model.ErrorClass;
import com.caygnus.webhook.delivery.application.ClaimedDelivery;
import com.caygnus.webhook.delivery.domain.DeliveryOutcome;
import com.caygnus.webhook.delivery.domain.ResponseClassifier;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import org.apache.hc.client5.http.ClientProtocolException;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.core5.http.ProtocolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The single place this system talks to the outside world.
 *
 * <p>It makes one call and reports what happened. It does not retry, does not decide what a
 * response means, and does not touch the database -- those belong to the retry policy, the
 * classifier and the outcome transaction respectively. Keeping it that narrow is what allows the
 * HTTP call to sit outside any transaction, which is the point of the whole arrangement.
 */
@Component
public class WebhookHttpClient {

    /** Enough of a response body to diagnose from. The rest is dropped, not buffered. */
    static final int MAX_BODY_SNIPPET_BYTES = 1024;

    private static final Logger log = LoggerFactory.getLogger(WebhookHttpClient.class);

    private final RestClient restClient;
    private final Clock clock;

    WebhookHttpClient(RestClient webhookRestClient, Clock clock) {
        this.restClient = webhookRestClient;
        this.clock = clock;
    }

    /**
     * Deliver one attempt.
     *
     * <p>Never throws for a delivery failure: a refused connection and a 500 are both ordinary
     * results here, and turning them into exceptions would only mean catching them again to
     * produce the same {@link DeliveryOutcome}.
     */
    public DeliveryOutcome send(ClaimedDelivery delivery) {
        try {
            return restClient.post()
                    .uri(delivery.targetUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> applyWebhookHeaders(headers, delivery))
                    .body(delivery.payload())
                    .exchange((request, response) -> DeliveryOutcome.fromResponse(
                            response.getStatusCode().value(),
                            readCappedBody(response.getBody()),
                            retryAfterSeconds(response.getHeaders())));
        } catch (Exception failure) {
            ErrorClass errorClass = classify(failure);
            log.warn("Delivery attempt {} for deliveryId={} failed in transport: {} ({})",
                    delivery.attemptNumber(), delivery.deliveryId(), errorClass, failure.getMessage());
            return DeliveryOutcome.fromTransportFailure(errorClass, failure.getMessage());
        }
    }

    /**
     * The contract a receiver sees.
     *
     * <p>{@code X-Idempotency-Key} is the delivery id, so it is <em>identical on every attempt</em>
     * of the same event. That is the whole of our at-least-once story: we cannot promise a receiver
     * will never see a duplicate, so we promise it can always recognise one.
     */
    private void applyWebhookHeaders(HttpHeaders headers, ClaimedDelivery delivery) {
        headers.add("X-Webhook-Event-Id", delivery.eventId());
        headers.add("X-Webhook-Delivery-Id", delivery.deliveryId().toString());
        headers.add("X-Idempotency-Key", delivery.deliveryId().toString());
        headers.add("X-Webhook-Attempt", String.valueOf(delivery.attemptNumber()));
        headers.add("X-Webhook-Timestamp", Instant.now(clock).toString());
        if (delivery.eventType() != null) {
            headers.add("X-Webhook-Event-Type", delivery.eventType());
        }
    }

    /**
     * Read at most {@link #MAX_BODY_SNIPPET_BYTES}, then stop.
     *
     * <p>A receiver is free to answer a failed webhook with a megabyte of HTML, and we store a
     * snippet per attempt for every delivery. Reading the whole thing to throw most of it away
     * would make our memory use a function of their error page. Leaving the remainder unread costs
     * at most that one pooled connection.
     */
    private static String readCappedBody(InputStream body) throws IOException {
        byte[] buffer = body.readNBytes(MAX_BODY_SNIPPET_BYTES);
        return buffer.length == 0 ? null : new String(buffer, StandardCharsets.UTF_8);
    }

    /**
     * {@code Retry-After} in delta-seconds form only. The HTTP-date form is not parsed: honouring
     * it correctly means trusting the receiver's clock against ours, and a wrong answer there
     * schedules an attempt at the wrong time rather than failing visibly.
     */
    private static Integer retryAfterSeconds(HttpHeaders headers) {
        String value = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            int seconds = Integer.parseInt(value.trim());
            return seconds >= 0 ? seconds : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Two things the pure classifier cannot see, because they are this client's own types.
     *
     * <p>A connect timeout and a read timeout are both {@link java.io.InterruptedIOException} to
     * the JDK, so only the adapter can tell them apart. Both are retryable, so that distinction is
     * purely for whoever reads the attempt history later.
     *
     * <p>A protocol error is the one that changes behaviour. With redirects and automatic retries
     * both disabled, the only way a plain POST reaches this state is a target the client cannot
     * build a request from at all -- "Target host is not specified" for a URL with no scheme, say.
     * That is a misconfigured endpoint, so it is permanent; left as {@code UNKNOWN} it would be
     * retried four more times to establish what the first attempt already did.
     */
    private static ErrorClass classify(Throwable failure) {
        for (Throwable cause = failure; cause != null && cause != cause.getCause(); cause = cause.getCause()) {
            if (cause instanceof ConnectTimeoutException) {
                return ErrorClass.CONNECT_TIMEOUT;
            }
            if (cause instanceof ProtocolException || cause instanceof ClientProtocolException) {
                return ErrorClass.MALFORMED_URL;
            }
        }
        return ResponseClassifier.classifyTransport(failure);
    }
}
