package com.caygnus.webhook.ingest.config;

import com.caygnus.webhook.ingest.application.OutboxDispatcher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Turns the dispatcher into something that ticks.
 *
 * <p>The same shape as the delivery service's scheduling: {@code @Scheduled} lives on a separate,
 * conditional trigger rather than on {@link OutboxDispatcher} itself, so the dispatcher is always
 * a bean a test can call by name, and only this component decides when production calls it.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {

    @Component
    @ConditionalOnProperty(name = "webhook.outbox.enabled", havingValue = "true", matchIfMissing = true)
    static class OutboxDispatcherTrigger {

        private final OutboxDispatcher dispatcher;

        OutboxDispatcherTrigger(OutboxDispatcher dispatcher) {
            this.dispatcher = dispatcher;
        }

        /**
         * {@code fixedDelay}: ticks must not overlap. Two passes at once would each claim their
         * own rows safely enough, but they would also each hold a transaction across HTTP, and
         * the bound on how long that lasts assumes one tick at a time.
         */
        @Scheduled(
                fixedDelayString = "${webhook.outbox.poll-interval}",
                initialDelayString = "${webhook.outbox.poll-interval}")
        void tick() {
            dispatcher.runOnce();
        }
    }
}
