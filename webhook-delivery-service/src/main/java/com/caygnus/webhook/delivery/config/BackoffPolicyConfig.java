package com.caygnus.webhook.delivery.config;

import com.caygnus.webhook.delivery.domain.BackoffPolicy;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Random;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Makes the retry policy a bean without letting Spring into the domain.
 *
 * <p>{@link BackoffPolicy} takes its clock and its randomness as arguments precisely so it can be
 * unit-tested with both pinned. This is the one place that supplies the real ones.
 */
@Configuration
public class BackoffPolicyConfig {

    @Bean
    BackoffPolicy backoffPolicy(RetryProperties properties, Clock clock) {
        // Thread-safe, and jitter has no security requirement -- any decent spread will do.
        Random random = new SecureRandom();
        return new BackoffPolicy(
                properties.baseDelay(),
                properties.multiplier(),
                properties.maxDelay(),
                properties.jitterFactor(),
                clock,
                random);
    }
}
