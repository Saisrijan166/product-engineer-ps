package com.caygnus.webhook.ingest.config;

import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The client for the one internal call this service makes.
 *
 * <p>Plainer than the delivery service's outbound client, and deliberately so: that one talks to
 * an arbitrary customer endpoint and needs a connection pool with a per-route cap to contain it.
 * This one talks to a service we operate, at one address, a few times a second. What it does need
 * is both timeouts, because the dispatcher holds a transaction open across this call.
 */
@Configuration
public class DeliveryServiceClientConfig {

    @Bean
    RestClient deliveryServiceRestClient(DeliveryServiceProperties properties) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build());
        requestFactory.setReadTimeout(properties.readTimeout());

        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
