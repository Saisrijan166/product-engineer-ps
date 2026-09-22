package com.caygnus.webhook.delivery.config;

import java.util.concurrent.TimeUnit;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The outbound HTTP client, configured for a receiver that may be hostile, broken or absent.
 *
 * <p>Every setting here is a containment decision rather than a tuning one.
 */
@Configuration
public class WebhookClientConfig {

    /** Total connections across all receivers. */
    private static final int MAX_CONNECTIONS = 20;

    /**
     * Connections to any single host. Below the worker count on purpose: it means one slow
     * receiver can occupy at most ten workers, and the rest stay free for anything else.
     */
    private static final int MAX_CONNECTIONS_PER_ROUTE = 10;

    @Bean
    RestClient webhookRestClient(DeliveryProperties properties) {
        return RestClient.builder()
                .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient(properties)))
                .build();
    }

    private CloseableHttpClient httpClient(DeliveryProperties properties) {
        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(MAX_CONNECTIONS)
                .setMaxConnPerRoute(MAX_CONNECTIONS_PER_ROUTE)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.of(properties.connectTimeout()))
                        .build())
                .build();

        RequestConfig requestConfig = RequestConfig.custom()
                .setResponseTimeout(Timeout.of(properties.responseTimeout()))
                // Waiting for a pooled connection is bounded too, so a saturated pool surfaces as a
                // prompt retryable failure instead of a worker parked indefinitely.
                .setConnectionRequestTimeout(Timeout.of(properties.connectTimeout().toMillis(), TimeUnit.MILLISECONDS))
                .build();

        return HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                // A 3xx from a webhook endpoint is a misconfiguration, not a delivery path.
                // Following one would post customer data somewhere the configuration never named.
                .disableRedirectHandling()
                // This engine owns retries. HttpClient retrying underneath would produce attempts
                // with no delivery_attempt row, quietly breaking both the bound and the history.
                .disableAutomaticRetries()
                .build();
    }
}
