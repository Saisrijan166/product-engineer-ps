package com.caygnus.webhook.receiver;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** The webhook target itself. Records what arrived, then answers however the mode says to. */
@RestController
public class ReceiverController {

    private static final Logger log = LoggerFactory.getLogger(ReceiverController.class);

    private final ReceiverState state;

    ReceiverController(ReceiverState state) {
        this.state = state;
    }

    @PostMapping("/receive")
    public ResponseEntity<Map<String, Object>> receive(
            @RequestBody(required = false) String body,
            @RequestHeader Map<String, String> headers,
            HttpServletRequest request) {

        ResponseDecision decision = state.recordAndDecide(
                request.getMethod(), request.getRequestURI(), new LinkedHashMap<>(headers), body);

        log.info("received seq={} idempotencyKey={} attempt={} -> status={} afterMs={}",
                decision.sequence(),
                headers.get("x-idempotency-key"),
                headers.get("x-webhook-attempt"),
                decision.status(),
                decision.delayMs());

        waitBeforeAnswering(decision.delayMs());

        ResponseEntity.BodyBuilder response = ResponseEntity.status(decision.status());
        if (decision.retryAfterSeconds() != null) {
            response.header("Retry-After", String.valueOf(decision.retryAfterSeconds()));
        }
        return response
                .body(Map.of(
                        "sequence", decision.sequence(),
                        "status", decision.status()));
    }

    /**
     * Being slow is this fixture's job -- it is how the read-timeout branch of the classifier gets
     * exercised at all. Note that this is the receiver stalling, not a test waiting on the clock;
     * the test asserts on the recorded outcome, never on elapsed time.
     */
    private static void waitBeforeAnswering(long delayMs) {
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            // Shutting down mid-stall is expected; stop waiting and answer immediately.
            Thread.currentThread().interrupt();
        }
    }
}
