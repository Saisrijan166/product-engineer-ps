package com.caygnus.webhook.common.model;

/**
 * Why an attempt produced no usable HTTP status. Recorded on the attempt row alongside a truncated
 * message so a reviewer can tell a refused connection from a read timeout without parsing free text.
 *
 * <p>{@code null} whenever the receiver did answer, however unhelpfully.
 */
public enum ErrorClass {

    /** The TCP connection could not be established within the connect timeout. */
    CONNECT_TIMEOUT,

    /** The connection was established but the response did not arrive within the response timeout. */
    READ_TIMEOUT,

    /** The host answered, actively refusing the connection. */
    CONNECTION_REFUSED,

    /** The target host could not be resolved. */
    DNS_FAILURE,

    /** The TLS handshake failed. Treated as permanent: a retry will fail identically. */
    TLS_ERROR,

    /**
     * The target URL could not be used at all. Treated as permanent: a misconfigured endpoint is
     * not a transient condition, and retrying it four more times only delays the diagnosis.
     */
    MALFORMED_URL,

    /** The endpoint health gate was open, so no HTTP call was made. The attempt is still recorded. */
    CIRCUIT_OPEN,

    /** Anything else. The message field carries the detail. */
    UNKNOWN
}
