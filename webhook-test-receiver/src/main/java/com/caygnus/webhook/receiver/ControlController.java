package com.caygnus.webhook.receiver;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Drives the receiver's behaviour from outside, so a scenario is set up by one HTTP call rather
 * than by restarting anything. Kept on a separate path from {@code /receive} so that control
 * traffic never lands in the delivery history.
 */
@RestController
@RequestMapping("/control")
public class ControlController {

    private static final Logger log = LoggerFactory.getLogger(ControlController.class);

    private final ReceiverState state;

    ControlController(ReceiverState state) {
        this.state = state;
    }

    /**
     * Set the mode. Only {@code mode} is required; the rest fall back to the per-mode defaults in
     * {@link ReceiverState#applyMode}, and the response echoes what was actually applied.
     */
    @PostMapping("/mode")
    public ReceiverState.ModeView setMode(@RequestBody ModeRequest request) {
        if (request == null || request.mode() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "mode is required: one of " + List.of(ReceiverMode.values()));
        }
        state.applyMode(request.mode(), request.failCount(), request.status(), request.delayMs(),
                request.retryAfterSeconds());

        ReceiverState.ModeView applied = state.currentMode();
        log.info("mode set to {}", applied);
        return applied;
    }

    /** Everything received since the last reset, oldest first, headers and body intact. */
    @GetMapping("/received")
    public Map<String, Object> received() {
        List<RecordedRequest> requests = state.recorded();
        return Map.of(
                "totalReceived", state.totalReceived(),
                "retained", requests.size(),
                "mode", state.currentMode(),
                "requests", requests);
    }

    /** Clear history and go back to the startup mode. */
    @PostMapping("/reset")
    public ReceiverState.ModeView reset() {
        state.reset();
        log.info("reset");
        return state.currentMode();
    }

    /**
     * @param failCount only meaningful for {@link ReceiverMode#FAIL_N_THEN_OK}
     * @param status    the status to answer with; for {@code FAIL_N_THEN_OK} it is the failing one
     * @param delayMs   wait this long before answering, in any mode
     * @param retryAfterSeconds returned as a Retry-After header on failures, so a scenario can
     *                          prove the engine honours the receiver's own backoff instruction
     */
    public record ModeRequest(
            ReceiverMode mode, Integer failCount, Integer status, Long delayMs, Integer retryAfterSeconds) {
    }
}
