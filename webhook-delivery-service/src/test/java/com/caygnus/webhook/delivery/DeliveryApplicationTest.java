package com.caygnus.webhook.delivery;

import com.caygnus.webhook.delivery.support.TestDatabase;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Smoke test for the delivery service, and rather more than that since step 4.
 *
 * <p>Starting the full context runs Flyway and then Hibernate's {@code ddl-auto: validate}, so this
 * test fails if the migration and the entity mapping ever disagree -- a renamed column, a type that
 * drifted, a field nobody added to the schema. That failure would otherwise surface at
 * {@code docker compose up}, which is a poor place to learn it.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        // The production wiring, on timers that will not fire before the suite ends. Disabling
        // the triggers outright would stop this test proving that they wire at all -- but leaving
        // them at their real intervals would be worse: Spring caches this context, so its reaper
        // would go on sweeping while other test classes are busy staging abandoned deliveries.
        properties = {"webhook.scheduler.poll-interval=1h", "webhook.reaper.interval=1h"})
class DeliveryApplicationTest {

    @Autowired
    private Clock clock;

    @Autowired
    private ApplicationContext context;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        TestDatabase.bind(registry);
    }

    @Test
    void contextLoadsWithAUtcClockAndASchemaThatMatchesTheEntities() {
        assertThat(clock).isNotNull();
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    void theProductionProfileGetsABoundedWorkerPoolAndAScheduledTrigger() {
        // The acceptance tests deliberately run with a synchronous executor and no trigger, so
        // without this nothing would ever exercise the wiring production actually uses.
        assertThat(context.getBean("deliveryTaskExecutor", TaskExecutor.class))
                .isInstanceOf(ThreadPoolTaskExecutor.class)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(ThreadPoolTaskExecutor.class))
                .satisfies(pool -> {
                    assertThat(pool.getMaxPoolSize()).isEqualTo(8);
                    // No queue of its own: work the pool cannot start must stay in PostgreSQL,
                    // where another instance can see it and a restart cannot lose it.
                    assertThat(pool.getThreadPoolExecutor().getQueue().remainingCapacity()).isZero();
                });

        // Asserting on the registered scheduled tasks rather than on the trigger beans: it is the
        // tasks actually being on a timer that matter, and a bean with a broken @Scheduled
        // expression would still exist. Two of them: the claim loop and the reaper.
        assertThat(context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks())
                .hasSize(2);
    }
}
