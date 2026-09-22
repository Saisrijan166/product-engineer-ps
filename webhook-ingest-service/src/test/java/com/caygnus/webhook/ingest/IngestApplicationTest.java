package com.caygnus.webhook.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import com.caygnus.webhook.ingest.support.TestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Smoke test for the ingest service, and rather more than that since step 9.
 *
 * <p>Starting the full context runs Flyway and then Hibernate's {@code ddl-auto: validate}, so this
 * test fails if the migration and the entity mapping ever disagree -- a renamed column, a type that
 * drifted, a field nobody added to the schema.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        // The production wiring, on a timer that will not fire before the suite ends. Disabling
        // the trigger outright would stop this test proving that it wires at all -- but leaving
        // it at its real 500ms interval is worse: Spring caches this context, so its dispatcher
        // goes on draining the outbox while other test classes are asserting on it.
        properties = "webhook.outbox.poll-interval=1h")
class IngestApplicationTest {

    @Autowired
    private Clock clock;

    @Autowired
    private ApplicationContext context;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        TestDatabase.bind(registry);
    }

    @Test
    void contextLoadsWithAUtcClock() {
        assertThat(clock).isNotNull();
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
    }
}
