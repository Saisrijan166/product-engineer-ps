package com.caygnus.webhook.delivery.config;

import com.caygnus.webhook.delivery.application.DeliveryScheduler;
import com.caygnus.webhook.delivery.application.LeaseReaper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Turns the scheduler into something that ticks.
 *
 * <p>The trigger is deliberately the thinnest possible wrapper. Keeping {@code @Scheduled} off
 * {@link DeliveryScheduler} itself is what lets a test own the clock: the scheduler is always a
 * bean and always callable, but only this component decides when production calls it. Disabling
 * the trigger is then a matter of one property rather than of stubbing out behaviour.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {

    @Component
    @ConditionalOnProperty(name = "webhook.scheduler.enabled", havingValue = "true", matchIfMissing = true)
    static class DeliverySchedulerTrigger {

        private final DeliveryScheduler scheduler;

        DeliverySchedulerTrigger(DeliveryScheduler scheduler) {
            this.scheduler = scheduler;
        }

        /**
         * {@code fixedDelay}, not {@code fixedRate}: ticks must never overlap. Two passes running
         * at once would each compute free capacity from the same stale reading and between them
         * claim more than the pool can start.
         */
        @Scheduled(
                fixedDelayString = "${webhook.scheduler.poll-interval}",
                initialDelayString = "${webhook.scheduler.poll-interval}")
        void tick() {
            scheduler.runOnce();
        }
    }

    /**
     * The reaper's timer, and its startup sweep.
     *
     * <p>Both hang off the same conditional so that disabling recovery is one decision rather than
     * two, and so a test that wants to drive the reaper by hand gets a bean with nothing ticking
     * behind it.
     */
    @Component
    @ConditionalOnProperty(name = "webhook.reaper.enabled", havingValue = "true", matchIfMissing = true)
    static class LeaseReaperTrigger {

        private final LeaseReaper reaper;

        LeaseReaperTrigger(LeaseReaper reaper) {
            this.reaper = reaper;
        }

        /**
         * Runs once the context is up, before this instance has claimed anything of its own.
         *
         * <p>{@link ApplicationReadyEvent} rather than a lifecycle callback: the datasource and
         * Flyway are both finished by then, and a sweep is pointless before the schema exists.
         */
        @EventListener(ApplicationReadyEvent.class)
        void recoverOnStartup() {
            reaper.recoverOnStartup();
        }

        @Scheduled(
                fixedDelayString = "${webhook.reaper.interval}",
                initialDelayString = "${webhook.reaper.interval}")
        void tick() {
            reaper.runOnce();
        }
    }
}
