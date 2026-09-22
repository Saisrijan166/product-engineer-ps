package com.caygnus.webhook.ingest.api;

import com.caygnus.webhook.ingest.api.dto.EventDetailView;
import com.caygnus.webhook.ingest.api.dto.EventPageView;
import com.caygnus.webhook.ingest.api.dto.IngestEventRequest;
import com.caygnus.webhook.ingest.api.dto.IngestEventResponse;
import com.caygnus.webhook.ingest.application.EventQueryService;
import com.caygnus.webhook.ingest.application.IngestEventUseCase;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The public face of the engine: submit an event, and see what has been submitted. */
@RestController
@RequestMapping("/api/v1/events")
public class EventController {

    private static final int MAX_PAGE_SIZE = 200;

    private final IngestEventUseCase ingestEvent;
    private final EventQueryService queries;

    EventController(IngestEventUseCase ingestEvent, EventQueryService queries) {
        this.ingestEvent = ingestEvent;
        this.queries = queries;
    }

    /**
     * Submit an event for delivery.
     *
     * <p>{@code 201} when this call accepted it, {@code 200} when it had already been accepted --
     * same event, same eventual delivery, either way. The status is there so a caller can tell
     * what happened, not so it has to care: retrying blindly is safe, which is the point.
     *
     * <p>{@code 409} only when the identifier is reused with a genuinely different payload. That
     * is a caller bug, and absorbing it would mean silently ignoring the new content or silently
     * replacing what is already on its way out.
     */
    @PostMapping
    public ResponseEntity<IngestEventResponse> ingest(@Valid @RequestBody IngestEventRequest request) {
        IngestEventUseCase.Result result = ingestEvent.ingest(request);

        if (result.accepted()) {
            return ResponseEntity
                    .created(URI.create("/api/v1/events/" + result.eventId()))
                    .body(IngestEventResponse.accepted(result.eventId()));
        }
        return ResponseEntity.status(HttpStatus.OK).body(IngestEventResponse.duplicate(
                result.eventId(), result.deliveryId(), result.duplicateSubmissionCount()));
    }

    /**
     * Everything known about one event: what was submitted, how the handoff went, and the
     * delivery with its whole attempt history, oldest attempt first.
     *
     * <p>Answers with a 200 even when the delivery service cannot be reached. What this service
     * knows is still correct, and {@code deliveryLookupError} says plainly what is missing --
     * failing outright would withhold the half of the answer that is available, at exactly the
     * moment someone is most likely to be asking.
     */
    @GetMapping("/{eventId}")
    public EventDetailView get(@PathVariable String eventId) {
        return queries.findByEventId(eventId);
    }

    /** Accepted events, newest first. */
    @GetMapping
    public EventPageView list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return queries.list(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
    }
}
