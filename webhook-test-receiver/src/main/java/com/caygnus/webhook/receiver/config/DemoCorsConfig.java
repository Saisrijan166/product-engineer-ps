package com.caygnus.webhook.receiver.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Cross-origin access for the demo UI, and for nothing else.
 *
 * <p>The UI is served by the ingest service on one port and calls this service on another, which
 * makes every one of those calls cross-origin. Rather than proxy them through ingest -- which
 * would mean production request-handling code existing only to serve a demo page -- this opens
 * CORS to localhost.
 *
 * <p><b>Profile-scoped on purpose.</b> {@code @Profile("demo")} means this bean does not exist
 * under the default profile, so a deployment that never opts into the demo has no relaxed
 * cross-origin policy to reason about, and no way to acquire one by accident. The demo UI is a
 * teaching aid; it should not be able to widen the security posture of a real run.
 *
 * <p>Origins are patterns rather than a wildcard because ports vary: a reviewer who hit a port
 * clash and moved the services still gets a working page, and {@code allowedOrigins("*")} would
 * be a broader grant than is needed.
 */
@Configuration
@Profile("demo")
public class DemoCorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*")
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*")
                // Nothing here is authenticated, so there are no credentials to send.
                .allowCredentials(false)
                .maxAge(1800);
    }
}
