package com.caygnus.webhook.delivery.domain;

import static com.caygnus.webhook.common.model.AttemptOutcome.PERMANENT_FAILURE;
import static com.caygnus.webhook.common.model.AttemptOutcome.RETRYABLE_FAILURE;
import static com.caygnus.webhook.common.model.AttemptOutcome.SUCCESS;
import static org.assertj.core.api.Assertions.assertThat;

import com.caygnus.webhook.common.model.AttemptOutcome;
import com.caygnus.webhook.common.model.ErrorClass;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Set;
import java.util.stream.IntStream;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Sweeps every status from 100 to 599, plus every transport failure.
 *
 * <p>The sweep checks against a whitelist restated here by hand rather than borrowed from the
 * classifier, so a code moving in or out of the retryable set fails this test rather than
 * propagating silently into the retry behaviour.
 */
class ResponseClassifierTest {

    /** ARCHITECTURE.md 9.1, transcribed by hand. */
    private static final Set<Integer> EXPECTED_RETRYABLE = Set.of(408, 425, 429, 500, 502, 503, 504);

    private static IntStream everyStatusCode() {
        return IntStream.rangeClosed(100, 599);
    }

    @ParameterizedTest(name = "HTTP {0}")
    @MethodSource("everyStatusCode")
    void everyStatusLandsOnTheDocumentedSideOfTheTable(int status) {
        AttemptOutcome expected;
        if (status >= 200 && status <= 299) {
            expected = SUCCESS;
        } else if (EXPECTED_RETRYABLE.contains(status)) {
            expected = RETRYABLE_FAILURE;
        } else {
            expected = PERMANENT_FAILURE;
        }

        assertThat(ResponseClassifier.classify(status)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "HTTP {0} is a success")
    @ValueSource(ints = {200, 201, 202, 204, 299})
    void theWholeTwoHundredRangeSucceeds(int status) {
        assertThat(ResponseClassifier.classify(status)).isEqualTo(SUCCESS);
    }

    @ParameterizedTest(name = "HTTP {0} is retryable")
    @ValueSource(ints = {408, 425, 429, 500, 502, 503, 504})
    void theSevenRetryableStatusesAreRetryable(int status) {
        assertThat(ResponseClassifier.classify(status)).isEqualTo(RETRYABLE_FAILURE);
    }

    @ParameterizedTest(name = "HTTP {0} is permanent: {1}")
    @CsvSource({
            "400, the request is wrong and will be wrong next time",
            "401, credentials will not appear on their own",
            "403, ditto",
            "404, the endpoint does not exist",
            "405, the endpoint does not accept POST",
            "409, the receiver rejected the state",
            "410, gone means gone",
            "413, the payload will be the same size on retry",
            "422, the body is wrong",
            "451, a legal block is not transient",
            "501, a receiver that has not implemented it will not have by the next attempt",
            "505, nor will it support the protocol version",
            "507, outside the whitelist, so not retried",
            "100, a webhook receiver has no business sending 1xx",
            "301, redirects are not followed, so a 3xx is a misconfiguration",
            "302, ditto",
            "307, ditto",
    })
    void namedPermanentStatusesAreNotRetried(int status, String why) {
        assertThat(ResponseClassifier.classify(status)).as(why).isEqualTo(PERMANENT_FAILURE);
    }

    @Test
    void retryingIsAWhitelistNotEverythingThatIsNotSuccessful() {
        long retryable = everyStatusCode().filter(s -> ResponseClassifier.classify(s) == RETRYABLE_FAILURE).count();

        assertThat(retryable).isEqualTo(EXPECTED_RETRYABLE.size());
    }

    @ParameterizedTest
    @EnumSource(ErrorClass.class)
    void everyErrorClassIsClassifiedAndOnlyConfigurationErrorsAreTerminal(ErrorClass errorClass) {
        AttemptOutcome outcome = ResponseClassifier.classify(errorClass);

        // Exhaustive over the enum, so adding a value without deciding its policy breaks the build.
        AttemptOutcome expected = switch (errorClass) {
            case TLS_ERROR, MALFORMED_URL -> PERMANENT_FAILURE;
            case CONNECT_TIMEOUT, READ_TIMEOUT, CONNECTION_REFUSED, DNS_FAILURE, CIRCUIT_OPEN, UNKNOWN ->
                    RETRYABLE_FAILURE;
        };
        assertThat(outcome).isEqualTo(expected);
        assertThat(outcome).isNotEqualTo(SUCCESS);
    }

    @Test
    void unknownTransportFailuresAreRetried() {
        // Losing an event costs more than four wasted calls against a bounded budget.
        assertThat(ResponseClassifier.classify(ErrorClass.UNKNOWN)).isEqualTo(RETRYABLE_FAILURE);
    }

    @Test
    void mapsDnsFailure() {
        assertThat(ResponseClassifier.classifyTransport(new UnknownHostException("receiver.invalid")))
                .isEqualTo(ErrorClass.DNS_FAILURE);
    }

    @Test
    void mapsConnectionRefused() {
        assertThat(ResponseClassifier.classifyTransport(new ConnectException("Connection refused")))
                .isEqualTo(ErrorClass.CONNECTION_REFUSED);
    }

    @Test
    void mapsReadTimeout() {
        assertThat(ResponseClassifier.classifyTransport(new SocketTimeoutException("Read timed out")))
                .isEqualTo(ErrorClass.READ_TIMEOUT);
    }

    @Test
    void mapsTlsFailure() {
        assertThat(ResponseClassifier.classifyTransport(new SSLHandshakeException("bad certificate")))
                .isEqualTo(ErrorClass.TLS_ERROR);
    }

    @Test
    void mapsMalformedUrl() {
        assertThat(ResponseClassifier.classifyTransport(new URISyntaxException("h ttp://", "illegal character")))
                .isEqualTo(ErrorClass.MALFORMED_URL);
        assertThat(ResponseClassifier.classifyTransport(new IllegalArgumentException("URI is not absolute")))
                .isEqualTo(ErrorClass.MALFORMED_URL);
    }

    @Test
    void connectionResetFallsThroughToUnknownAndIsStillRetried() {
        // No dedicated ErrorClass value: the message carries the detail, and the policy -- retry --
        // is the same as for any other unrecognised transport fault.
        ErrorClass errorClass = ResponseClassifier.classifyTransport(new SocketException("Connection reset"));

        assertThat(errorClass).isEqualTo(ErrorClass.UNKNOWN);
        assertThat(ResponseClassifier.classify(errorClass)).isEqualTo(RETRYABLE_FAILURE);
    }

    @Test
    void unwrapsTheCauseChainBecauseHttpClientsWrap() {
        Throwable wrapped = new IllegalStateException("delivery failed",
                new UncheckedIOException(new IOException("transport", new UnknownHostException("receiver.invalid"))));

        assertThat(ResponseClassifier.classifyTransport(wrapped)).isEqualTo(ErrorClass.DNS_FAILURE);
    }

    @Test
    void anUnrecognisedFailureIsUnknownRatherThanAnExplosion() {
        assertThat(ResponseClassifier.classifyTransport(new RuntimeException("something new")))
                .isEqualTo(ErrorClass.UNKNOWN);
    }

    @Test
    void survivesASelfReferentialCauseChain() {
        Exception looping = new Exception("loops") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };

        assertThat(ResponseClassifier.classifyTransport(looping)).isEqualTo(ErrorClass.UNKNOWN);
    }
}
