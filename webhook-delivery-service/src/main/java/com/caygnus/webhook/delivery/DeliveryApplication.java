package com.caygnus.webhook.delivery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Owns the delivery lifecycle: claiming due work, performing the HTTP attempt, classifying the
 * outcome, and scheduling the next attempt or terminating.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class DeliveryApplication {

    public static void main(String[] args) {
        SpringApplication.run(DeliveryApplication.class, args);
    }
}
