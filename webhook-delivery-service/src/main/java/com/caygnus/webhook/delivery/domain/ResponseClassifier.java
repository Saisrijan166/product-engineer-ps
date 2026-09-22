package com.caygnus.webhook.delivery.domain;

import com.caygnus.webhook.common.model.AttemptOutcome;
import com.caygnus.webhook.common.model.ErrorClass;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.Set;
import javax.net.ssl.SSLException;

/**
 * The retry policy, as one pure function: given what came back, should we try again?
 *
 * <p>The rule behind the table is that we retry only what a later attempt could plausibly succeed
 * at. A 422 means the body is wrong and it will be equally wrong in ten minutes; retrying it burns
 * an attempt, delays the deliveries that could have succeeded, and hits the receiver for nothing.
 * So retryability is a short whitelist, not "everything that is not a 2xx".
 *
 * <p>Deliberately static and dependency-free. This is the part of the system a reviewer should be
 * able to read once and fully believe, and it is the part the tests hit hardest.
 */
public final class ResponseClassifier {

    /**
     * The whitelist. Everything outside it that is not a 2xx is permanent, which is why 501 and
     * 505 need no special case: a receiver that has not implemented the endpoint will not have
     * implemented it by the next attempt either.
     */
    private static final Set<Integer> RETRYABLE_STATUSES = Collections.unmodifiableSet(Set.of(
            408,  // Request Timeout
            425,  // Too Early
            429,  // Too Many Requests
            500,  // Internal Server Error
            502,  // Bad Gateway
            503,  // Service Unavailable
            504)); // Gateway Timeout

    private ResponseClassifier() {
    }

    /**
     * Classify a response the receiver actually sent.
     *
     * <p>Redirects count as permanent because the client does not follow them: a 3xx from a webhook
     * endpoint is a misconfiguration, not a delivery path, and silently following one would mean
     * posting customer data somewhere the configuration never named.
     */
    public static AttemptOutcome classify(int httpStatus) {
        if (httpStatus >= 200 && httpStatus <= 299) {
            return AttemptOutcome.SUCCESS;
        }
        return RETRYABLE_STATUSES.contains(httpStatus)
                ? AttemptOutcome.RETRYABLE_FAILURE
                : AttemptOutcome.PERMANENT_FAILURE;
    }

    /**
     * Classify a failure that produced no status at all.
     *
     * <p>{@link ErrorClass#UNKNOWN} is retryable on purpose. The two errors are asymmetric: not
     * retrying a transient fault loses an event, while retrying a permanent one costs at most four
     * more calls before the bound stops it. Connection resets land here and are retried, as the
     * policy says they should be.
     */
    public static AttemptOutcome classify(ErrorClass errorClass) {
        return switch (errorClass) {
            case CONNECT_TIMEOUT, READ_TIMEOUT, CONNECTION_REFUSED, DNS_FAILURE, CIRCUIT_OPEN, UNKNOWN ->
                    AttemptOutcome.RETRYABLE_FAILURE;
            case TLS_ERROR, MALFORMED_URL -> AttemptOutcome.PERMANENT_FAILURE;
        };
    }

    /**
     * Best-effort mapping from a thrown exception to a recorded {@link ErrorClass}, walking the
     * cause chain because HTTP clients wrap.
     *
     * <p>Only JDK types are matched, so this stays free of the client library. That costs one
     * distinction: a connect timeout and a read timeout are both {@link SocketTimeoutException}
     * here and come back as {@code READ_TIMEOUT}. The transport adapter knows its own client's
     * connect-timeout type and passes {@link ErrorClass#CONNECT_TIMEOUT} directly when it can. The
     * difference is diagnostic only -- both are retryable.
     */
    public static ErrorClass classifyTransport(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            ErrorClass matched = matchExactly(cause);
            if (matched != null) {
                return matched;
            }
            if (cause.getCause() == cause) {
                break; // self-referential cause chains exist in the wild
            }
        }
        return ErrorClass.UNKNOWN;
    }

    private static ErrorClass matchExactly(Throwable cause) {
        // UnknownHostException before ConnectException, and ConnectException before the timeouts:
        // these are not disjoint types, so order is the classification.
        if (cause instanceof UnknownHostException) {
            return ErrorClass.DNS_FAILURE;
        }
        if (cause instanceof SSLException) {
            return ErrorClass.TLS_ERROR;
        }
        if (cause instanceof ConnectException) {
            return ErrorClass.CONNECTION_REFUSED;
        }
        if (cause instanceof SocketTimeoutException) {
            return ErrorClass.READ_TIMEOUT;
        }
        if (cause instanceof URISyntaxException || cause instanceof IllegalArgumentException) {
            return ErrorClass.MALFORMED_URL;
        }
        return null;
    }
}
