package com.caygnus.webhook.delivery.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The one real PostgreSQL every database-backed test in this module talks to.
 *
 * <p><b>Never H2.</b> Everything these tests prove -- {@code FOR UPDATE SKIP LOCKED},
 * {@code ON CONFLICT DO NOTHING}, partial indexes, the check constraints -- either does not exist
 * on an embedded database or behaves differently there. A green suite against H2 would be evidence
 * about H2.
 *
 * <p>By default that is a Testcontainers {@code postgres:16-alpine}, started once per JVM and
 * removed by Testcontainers' own reaper at exit. This is the path a reviewer takes, and the only
 * one the documented setup mentions.
 *
 * <p>Setting {@code TEST_DB_URL} points the suite at an already-running PostgreSQL 16 instead. Two
 * reasons it exists, neither of them convenience: CI runners often provide a database as a service
 * rather than a Docker socket, and a developer whose account is not in the {@code docker} group can
 * still run the full suite against a local server. It is the same schema, the same migrations and
 * the same assertions either way -- what changes is who started the server, not what is tested.
 */
public final class TestDatabase {

    private static final String URL_ENV = "TEST_DB_URL";
    private static final String USER_ENV = "TEST_DB_USER";
    private static final String PASSWORD_ENV = "TEST_DB_PASSWORD";

    private static final String url = System.getenv(URL_ENV);
    private static final boolean external = url != null && !url.isBlank();

    /**
     * Held in a holder class so the container is constructed only when it is actually needed.
     * Touching {@link PostgreSQLContainer} at all would otherwise make an external-database run
     * depend on a Docker socket it was chosen precisely to avoid.
     */
    private static final class Container {
        static final PostgreSQLContainer<?> INSTANCE =
                new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

        static {
            INSTANCE.start();
        }
    }

    private TestDatabase() {
    }

    /** Binds the datasource for a test context. Call from a {@code @DynamicPropertySource} method. */
    public static void bind(DynamicPropertyRegistry registry) {
        if (external) {
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.username", () -> envOrDefault(USER_ENV, "webhook"));
            registry.add("spring.datasource.password", () -> envOrDefault(PASSWORD_ENV, "webhook"));
            return;
        }
        registry.add("spring.datasource.url", Container.INSTANCE::getJdbcUrl);
        registry.add("spring.datasource.username", Container.INSTANCE::getUsername);
        registry.add("spring.datasource.password", Container.INSTANCE::getPassword);
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
