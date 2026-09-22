package com.caygnus.webhook.delivery.config;

import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import com.caygnus.webhook.delivery.infrastructure.observability.MdcTaskDecorator;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Where delivery attempts actually run.
 *
 * <p>Two implementations, chosen by profile, and the difference is what makes the acceptance tests
 * deterministic. In production this is a bounded pool; under {@code test} it runs the attempt on
 * the calling thread, so {@code scheduler.runOnce()} returns only once every delivery it claimed
 * has finished. A test can then assert on the result immediately -- no polling, no Awaitility, no
 * sleeping and hoping.
 */
@Configuration
public class ExecutorConfig {

    /**
     * A fixed pool with no queue of its own.
     *
     * <p>The {@link SynchronousQueue} is the point: a task is either started at once or refused.
     * A buffered queue would accept work the pool cannot run, and that work would then exist only
     * in this process's memory -- invisible to other instances, and gone on restart, while the
     * database still shows it as claimed and in flight. Refusing instead lets the scheduler hand
     * the claim straight back.
     */
    /**
     * Both beans carry this name so that the injection point never has to know which profile is
     * active -- and so that it is unambiguous against the {@code taskScheduler} that
     * {@code @EnableScheduling} contributes, which is a {@link TaskExecutor} too.
     */
    static final String BEAN_NAME = "deliveryTaskExecutor";

    @Bean(BEAN_NAME)
    @Profile("!test")
    TaskExecutor deliveryTaskExecutor(DeliveryProperties properties, MdcTaskDecorator mdcTaskDecorator) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.maxConcurrentAttempts());
        executor.setMaxPoolSize(properties.maxConcurrentAttempts());
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("delivery-");
        // Carries the delivery's logging context across the thread boundary.
        executor.setTaskDecorator(mdcTaskDecorator);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        // On shutdown, let attempts that have already started finish and write their outcome.
        // Killing one mid-flight is survivable -- the reaper handles it -- but it costs the
        // receiver a duplicate for no reason.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds((int) properties.responseTimeout().plusSeconds(5).toSeconds());
        return executor;
    }

    /**
     * Runs on the calling thread, and still decorates: the MDC behaviour under test should be the
     * production one, and a task that cleared the caller's context would be a real bug.
     */
    @Bean(BEAN_NAME)
    @Profile("test")
    TaskExecutor syncDeliveryTaskExecutor(MdcTaskDecorator mdcTaskDecorator) {
        SyncTaskExecutor executor = new SyncTaskExecutor();
        return task -> executor.execute(mdcTaskDecorator.decorate(task));
    }
}
