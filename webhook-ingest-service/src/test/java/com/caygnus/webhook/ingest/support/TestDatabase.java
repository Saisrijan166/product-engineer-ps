package com.caygnus.webhook.ingest.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The real PostgreSQL every database-backed test in this module talks to.
 *
 * <p><b>Never H2.</b> {@code INSERT ... ON CONFLICT DO NOTHING} is the whole of this service's
 * idempotency, and the atomic counter increment behind it is a PostgreSQL row lock. A green suite
 * against an embedded database would be evidence about the embedded database.
 *
 * <p>By default a Testcontainers {@code postgres:16-alpine}, which is the path a reviewer takes.
 * {@code TEST_DB_URL} points the suite at an already-running server instead -- for CI runners that
 * offer a database rather than a Docker socket, and for developers whose account is not in the
 * {@code docker} group.
 *
 * <p>The two services own separate databases, and that matters even in tests: each runs its own
 * Flyway migrations against its own {@code flyway_schema_history}, so sharing one database would
 * have them fighting over it. When {@code TEST_DB_URL} names the delivery database, this swaps in
 * the ingest one rather than asking for a second variable -- the local setup script creates both.
 */
public final class TestDatabase {

    private static final String INGEST_DATABASE = "webhook_ingest";

    private static final String url = resolveUrl();
    private static final boolean external = url != null;

    private static final class Container {
        static final PostgreSQLContainer<?> INSTANCE =
                new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

        static {
            INSTANCE.start();
        }
    }

    private TestDatabase() {
    }

    public static void bind(DynamicPropertyRegistry registry) {
        if (external) {
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.username", () -> envOrDefault("TEST_DB_USER", "webhook"));
            registry.add("spring.datasource.password", () -> envOrDefault("TEST_DB_PASSWORD", "webhook"));
            return;
        }
        registry.add("spring.datasource.url", Container.INSTANCE::getJdbcUrl);
        registry.add("spring.datasource.username", Container.INSTANCE::getUsername);
        registry.add("spring.datasource.password", Container.INSTANCE::getPassword);
    }

    /**
     * {@code TEST_INGEST_DB_URL} wins if set. Otherwise {@code TEST_DB_URL} has its database name
     * replaced with {@code webhook_ingest}, so one variable configures both modules and neither
     * ends up migrating the other's schema.
     */
    private static String resolveUrl() {
        String explicit = System.getenv("TEST_INGEST_DB_URL");
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        String shared = System.getenv("TEST_DB_URL");
        if (shared == null || shared.isBlank()) {
            return null;
        }
        return shared.replaceFirst("/[^/?]+(\\?|$)", "/" + INGEST_DATABASE + "$1");
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
