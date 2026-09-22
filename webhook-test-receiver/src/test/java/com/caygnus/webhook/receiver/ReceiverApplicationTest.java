package com.caygnus.webhook.receiver;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test for the test receiver: the context starts and time is injectable.
 *
 * <p>It exists so that {@code mvn verify} fails on a broken wiring change rather than at the first
 * {@code docker compose up}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ReceiverApplicationTest {

    @Autowired
    private Clock clock;

    @Test
    void contextLoadsWithAUtcClock() {
        assertThat(clock).isNotNull();
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
    }
}
