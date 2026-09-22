package com.caygnus.webhook.delivery.support;

import com.caygnus.webhook.receiver.ReceiverApplication;
import java.util.List;
import java.util.Map;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

/**
 * The real {@code webhook-test-receiver}, started once for the acceptance tests.
 *
 * <p>Deliberately the actual service rather than a mock server. What these tests prove -- that the
 * headers arrive, that a 503 comes back as a 503, that the receiver is hit exactly once -- is only
 * worth proving against the thing a reviewer will run in the demo. A stub would let the two drift
 * apart, and the drift would show up on camera rather than here.
 *
 * <p>It runs in this JVM on a random port, so several test classes can share it without colliding
 * with a developer's own receiver on 8082.
 */
public final class TestReceiver {

    private static ConfigurableApplicationContext context;
    private static String baseUrl;
    private static RestClient client;

    private TestReceiver() {
    }

    /** Starts the receiver on first use; later calls are no-ops. */
    public static synchronized void start() {
        if (context != null) {
            return;
        }
        // Passed as command-line arguments rather than through .properties(): the latter becomes
        // a *default* property source, which loses to any application.yml on the classpath -- and
        // on this classpath that is the delivery service's, which names port 8081.
        context = new SpringApplicationBuilder(ReceiverApplication.class)
                .run(
                        "--server.port=0",
                        "--spring.main.banner-mode=off",
                        // The receiver is an in-memory fixture with no database of its own. Running
                        // it inside this JVM puts it on the delivery service's test classpath,
                        // where JPA and Flyway would otherwise autoconfigure themselves and try to
                        // open a connection it has no use for.
                        "--spring.autoconfigure.exclude="
                                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                                + "org.springframework.boot.autoconfigure.jdbc"
                                + ".DataSourceTransactionManagerAutoConfiguration,"
                                + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
                        // Both modules ship an application.yml, and which one wins on a shared
                        // classpath is down to ordering. Stating the receiver's own settings here
                        // means this fixture does not depend on that.
                        "--receiver.default-mode=ALWAYS_OK",
                        "--receiver.default-status=200",
                        "--receiver.max-recorded-requests=500");
        int port = context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
        baseUrl = "http://127.0.0.1:" + port;
        client = RestClient.create(baseUrl);
    }

    /** Where the delivery service should send webhooks. */
    public static String receiveUrl() {
        start();
        return baseUrl + "/receive";
    }

    /** Clear the history and go back to answering 200. Call this in {@code @BeforeEach}. */
    public static void reset() {
        start();
        client.post().uri("/control/reset").retrieve().toBodilessEntity();
    }

    public static void alwaysOk() {
        setMode(Map.of("mode", "ALWAYS_OK"));
    }

    public static void alwaysFail(int status) {
        setMode(Map.of("mode", "ALWAYS_FAIL", "status", status));
    }

    public static void failThenSucceed(int failures, int status) {
        setMode(Map.of("mode", "FAIL_N_THEN_OK", "failCount", failures, "status", status));
    }

    /** Fail every time, telling the caller when to come back via a Retry-After header. */
    public static void alwaysFailWithRetryAfter(int status, int retryAfterSeconds) {
        setMode(Map.of("mode", "ALWAYS_FAIL", "status", status, "retryAfterSeconds", retryAfterSeconds));
    }

    /** Answer only after {@code delayMs}, to drive the read-timeout branch of the classifier. */
    public static void stallFor(long delayMs) {
        setMode(Map.of("mode", "TIMEOUT", "delayMs", delayMs));
    }

    private static void setMode(Map<String, Object> mode) {
        start();
        client.post().uri("/control/mode").body(mode).retrieve().toBodilessEntity();
    }

    /** Everything the receiver has been sent since the last reset, oldest first. */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> received() {
        start();
        Map<String, Object> body = client.get().uri("/control/received").retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
        return (List<Map<String, Object>>) body.get("requests");
    }

    /** How many requests arrived -- unaffected by the history being trimmed. */
    public static int totalReceived() {
        start();
        Map<String, Object> body = client.get().uri("/control/received").retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
        return ((Number) body.get("totalReceived")).intValue();
    }

    /** The headers of the nth request, 1-based. Keys are lower-case, as the servlet reports them. */
    @SuppressWarnings("unchecked")
    public static Map<String, String> headersOf(int requestNumber) {
        return (Map<String, String>) received().get(requestNumber - 1).get("headers");
    }

    public static String bodyOf(int requestNumber) {
        return (String) received().get(requestNumber - 1).get("body");
    }
}
