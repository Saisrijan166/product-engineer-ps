package com.caygnus.webhook.ingest.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Invariant 7: time is injected, never read from a static.
 *
 * <p>Nothing in this service calls {@code Instant.now()}; everything takes this bean. That is what
 * lets the acceptance tests replace it with a mutable clock and step retry backoff forward
 * instantly, instead of sleeping through it and hoping.
 *
 * <p>UTC, not the system zone: every timestamp we persist is a {@code timestamptz} and every
 * timestamp we emit is RFC 3339. There is no business reason for a local zone to enter the system.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
