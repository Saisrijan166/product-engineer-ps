package com.caygnus.webhook.receiver;

/** How the receiver answers the next request. Driven by {@code POST /control/mode}. */
public enum ReceiverMode {

    /** Answer {@code status} (default 200) every time. */
    ALWAYS_OK,

    /** Answer {@code status} (default 500) every time -- drives the attempt-exhaustion scenario. */
    ALWAYS_FAIL,

    /**
     * Answer {@code status} (default 500) for the first {@code failCount} requests, then 200 --
     * drives the temporary-failure-then-retry scenario.
     */
    FAIL_N_THEN_OK,

    /**
     * Answer only after {@code delayMs} (default 30s), long past the delivery service's response
     * timeout -- drives the read-timeout branch of the classifier.
     */
    TIMEOUT
}
