package com.caygnus.webhook.ingest.api;

import com.caygnus.webhook.ingest.config.UiConfigProperties;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tells the demo UI which ports to call.
 *
 * <p><b>Exists for the demo page and nothing else.</b> The page is served from this service but
 * has to reach the delivery service and the receiver too, and hardcoding 8081 and 8082 would
 * break for anyone who moved them after a port clash -- which the setup instructions explicitly
 * invite. This lets the page discover them instead.
 *
 * <p>It returns configuration, never state, and nothing in the engine reads it. Removing this
 * class and the {@code static/} directory would remove the UI entirely and change no behaviour.
 */
@RestController
public class UiConfigController {

    private final UiConfigProperties properties;
    private final String activeProfiles;

    UiConfigController(
            UiConfigProperties properties,
            org.springframework.core.env.Environment environment) {
        this.properties = properties;
        this.activeProfiles = String.join(",", environment.getActiveProfiles());
    }

    /**
     * @return the browser-reachable base URLs, plus the active profile so the page can warn when
     *         cross-origin calls are about to fail because {@code demo} is not on
     */
    @GetMapping("/api/v1/ui-config")
    public Map<String, String> uiConfig() {
        return Map.of(
                "deliveryBaseUrl", properties.deliveryBaseUrl(),
                "receiverBaseUrl", properties.receiverBaseUrl(),
                "activeProfiles", activeProfiles);
    }
}
