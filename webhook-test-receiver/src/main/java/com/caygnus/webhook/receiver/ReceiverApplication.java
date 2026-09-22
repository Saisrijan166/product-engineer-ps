package com.caygnus.webhook.receiver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * A webhook target whose failure behaviour the demo and the acceptance tests drive on purpose.
 * Test fixture, not product code: everything it remembers is in memory and dies with the process.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ReceiverApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReceiverApplication.class, args);
    }
}
