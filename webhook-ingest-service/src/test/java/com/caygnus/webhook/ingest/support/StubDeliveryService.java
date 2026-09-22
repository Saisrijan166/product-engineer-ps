package com.caygnus.webhook.ingest.support;

import com.caygnus.webhook.common.api.DeliveryListView;
import com.caygnus.webhook.common.api.DeliveryView;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A delivery service that can be made to fail on demand.
 *
 * <p>A stub rather than the real service, and the choice is deliberate. What these tests prove is
 * about the <em>outbox</em>: that an intent survives the far end being down, backs off, and goes
 * out when it returns. Standing up the real delivery service here would drag in its database, its
 * schema and its scheduler to exercise none of them, and would make "return 503 five times, then
 * recover" awkward to arrange. Its own behaviour -- that a repeated create is idempotent -- is
 * already proven against a real PostgreSQL in {@code DeliveryApiTest}.
 *
 * <p>The JDK's own HTTP server, so there is no extra dependency and nothing to start beyond a
 * thread. One instance per JVM on a random port.
 */
public final class StubDeliveryService {

    private static HttpServer server;
    private static String baseUrl;
    private static int port;

    /** What the next request gets. Swapped mid-test to simulate the service coming back. */
    private static final AtomicReference<Behaviour> behaviour = new AtomicReference<>(Behaviour.accept());

    private static final List<Map<String, String>> requests = new CopyOnWriteArrayList<>();

    /**
     * Configured the same way Spring Boot configures the service's own mapper, so what this stub
     * writes is what the real delivery service would write -- ISO-8601 instants rather than epoch
     * numbers. A mismatch here would have the client failing to parse a shape it handles fine in
     * production.
     */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    /** What a lookup returns. Empty by default: reachable, with nothing to report. */
    private static final AtomicReference<String> lookupBody =
            new AtomicReference<>("{\"deliveries\":[],\"count\":0}");

    private StubDeliveryService() {
    }

    public static synchronized void start() {
        if (server != null) {
            return;
        }
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start the stub delivery service", e);
        }
        server.createContext("/internal/v1/deliveries", StubDeliveryService::handle);
        server.start();
        port = server.getAddress().getPort();
        baseUrl = "http://127.0.0.1:" + port;
    }

    /**
     * Take the service off the network entirely, so the dispatcher gets a refused connection
     * rather than an HTTP error. Distinct from {@link #unavailable()} on purpose: a 503 and a
     * dead socket arrive as completely different things and are both worth proving retryable.
     */
    public static synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    /** Bring it back on the same port, so the configured base URL is still good. */
    public static synchronized void restart() {
        if (server != null) {
            return;
        }
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not restart the stub delivery service", e);
        }
        server.createContext("/internal/v1/deliveries", StubDeliveryService::handle);
        server.start();
    }

    public static String baseUrl() {
        start();
        return baseUrl;
    }

    /** Back to healthy, running, with no history. Call this in {@code @BeforeEach}. */
    public static void reset() {
        start();
        restart();
        behaviour.set(Behaviour.accept());
        holdingNothing();
        requests.clear();
    }

    /** Acknowledge everything, as a healthy delivery service would. */
    public static void available() {
        behaviour.set(Behaviour.accept());
    }

    /** The service is down: a 503 the dispatcher should treat as worth retrying. */
    public static void unavailable() {
        behaviour.set(new Behaviour(503, "{\"title\":\"Service Unavailable\"}", null));
    }

    /** Unwell in some other 5xx way. */
    public static void failing(int status) {
        behaviour.set(new Behaviour(status, "{\"title\":\"Server Error\"}", null));
    }

    /** The service rejects the request itself -- a caller bug, not worth retrying. */
    public static void rejecting(int status) {
        behaviour.set(new Behaviour(status, "{\"title\":\"Bad Request\"}", null));
    }

    /** Answer lookups with this delivery and its history, as the real service would. */
    public static void holdingDelivery(DeliveryView delivery) {
        lookupBody.set(write(DeliveryListView.of(List.of(delivery))));
    }

    /** Reachable, but with no delivery for the event -- the ordinary pre-dispatch state. */
    public static void holdingNothing() {
        lookupBody.set(write(DeliveryListView.of(List.of())));
    }

    /** Acknowledge, but answer with a specific delivery id so a test can assert on it. */
    public static void acceptingAs(UUID deliveryId) {
        behaviour.set(new Behaviour(201, null, deliveryId));
    }

    /** Every request the stub has been sent since the last reset, oldest first. */
    public static List<Map<String, String>> received() {
        return List.copyOf(requests);
    }

    public static int totalReceived() {
        return requests.size();
    }

    private static void handle(HttpExchange exchange) throws IOException {
        if ("GET".equals(exchange.getRequestMethod())) {
            // A service that is unwell is unwell for everything it serves, so a 5xx behaviour
            // covers lookups too. A 4xx does not: that is about a particular request being
            // wrong, not about the service being down.
            Behaviour current = behaviour.get();
            if (current.status() >= 500) {
                respond(exchange, current.status(), current.responseBody());
            } else {
                respond(exchange, 200, lookupBody.get());
            }
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(Map.of(
                "idempotencyKey", String.valueOf(exchange.getRequestHeaders().getFirst("Idempotency-Key")),
                "contentType", String.valueOf(exchange.getRequestHeaders().getFirst("Content-Type")),
                "body", body));

        Behaviour current = behaviour.get();
        respond(exchange, current.status(), current.responseBody());
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, response.length);
        try (var out = exchange.getResponseBody()) {
            out.write(response);
        }
    }

    private static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write a stub response", e);
        }
    }

    /**
     * @param fixedBody   a literal response, or null to synthesise an acknowledgement
     * @param deliveryId  the id to acknowledge with; a fresh one per request when null
     */
    private record Behaviour(int status, String fixedBody, UUID deliveryId) {

        static Behaviour accept() {
            return new Behaviour(201, null, null);
        }

        String responseBody() {
            if (fixedBody != null) {
                return fixedBody;
            }
            UUID id = deliveryId != null ? deliveryId : UUID.randomUUID();
            return "{\"deliveryId\":\"%s\",\"status\":\"PENDING\"}".formatted(id);
        }
    }
}
