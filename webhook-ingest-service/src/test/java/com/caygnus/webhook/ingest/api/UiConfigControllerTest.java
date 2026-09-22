package com.caygnus.webhook.ingest.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.caygnus.webhook.ingest.config.UiConfigProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The demo UI's discovery endpoint.
 *
 * <p>Small, but worth a test for one reason: it returns the addresses a <em>browser</em> uses,
 * which are not the addresses this service uses to reach its neighbours. Those two are easy to
 * confuse, and confusing them produces a page that works on the developer's machine and fails
 * inside Docker, where {@code delivery-service:8081} resolves on the compose network and nowhere
 * a browser can see.
 */
@WebMvcTest(UiConfigController.class)
@EnableConfigurationProperties(UiConfigProperties.class)
@TestPropertySource(properties = {
        "webhook.ui.delivery-base-url=http://localhost:9091",
        "webhook.ui.receiver-base-url=http://localhost:9092",
        "spring.main.allow-bean-definition-overriding=true"})
class UiConfigControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void itReportsTheBrowserReachableAddressesAndTheActiveProfile() throws Exception {
        mockMvc.perform(get("/api/v1/ui-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryBaseUrl").value("http://localhost:9091"))
                .andExpect(jsonPath("$.receiverBaseUrl").value("http://localhost:9092"))
                // Present so the page can explain itself when cross-origin calls are about to
                // fail because the demo profile -- and with it the CORS config -- is not on.
                .andExpect(jsonPath("$.activeProfiles").exists());
    }
}
