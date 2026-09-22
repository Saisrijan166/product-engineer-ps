package com.caygnus.webhook.ingest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Owns event ingestion: idempotent acceptance, durable retention, and the outbox handoff to the
 * delivery service. Stays available when the receiver — or the delivery service — is not.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class IngestApplication {

    public static void main(String[] args) {
        SpringApplication.run(IngestApplication.class, args);
    }
}
