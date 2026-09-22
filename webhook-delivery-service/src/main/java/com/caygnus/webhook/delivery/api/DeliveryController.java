package com.caygnus.webhook.delivery.api;

import com.caygnus.webhook.common.api.AttemptView;
import com.caygnus.webhook.common.api.CreateDeliveryRequest;
import com.caygnus.webhook.common.api.DeliveryListView;
import com.caygnus.webhook.common.api.DeliveryView;
import com.caygnus.webhook.common.model.DeliveryStatus;
import com.caygnus.webhook.delivery.application.CreateDeliveryUseCase;
import com.caygnus.webhook.delivery.application.DeliveryQueryService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The delivery service's own API.
 *
 * <p>Under {@code /internal} because it is service-to-service: the ingest service dispatches to it
 * and composes reads from it. It is also the most direct way to inspect the engine during a demo,
 * which is why the read endpoints return everything that was recorded rather than a summary.
 */
@RestController
@RequestMapping("/internal/v1/deliveries")
public class DeliveryController {

    /** Bounds a listing so the dead-letter view cannot accidentally return the whole table. */
    private static final int MAX_LIMIT = 200;

    private static final int DEFAULT_LIMIT = 50;

    private final CreateDeliveryUseCase createDelivery;
    private final DeliveryQueryService queries;

    DeliveryController(CreateDeliveryUseCase createDelivery, DeliveryQueryService queries) {
        this.createDelivery = createDelivery;
        this.queries = queries;
    }

    /**
     * Schedule a delivery for an event, idempotently.
     *
     * <p>Answers {@code 201} when this call created the delivery and {@code 200} when one already
     * existed, with the same {@code deliveryId} either way. The distinction is information, not
     * control flow: a caller that ignores it and just reads the body is still correct, which is
     * what makes the dispatcher safe to retry.
     *
     * @param idempotencyKey optional, and expected to equal the body's {@code eventId}. The body is
     *                       what is actually used; the header is a restatement, checked so that a
     *                       caller sending mismatched values learns about it here rather than
     *                       through a mysteriously duplicated event later.
     */
    @PostMapping
    public ResponseEntity<DeliveryView> create(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateDeliveryRequest request) {

        if (idempotencyKey != null && !idempotencyKey.equals(request.eventId())) {
            throw new IdempotencyKeyMismatchException(idempotencyKey, request.eventId());
        }

        CreateDeliveryUseCase.Result result = createDelivery.create(request);
        DeliveryView view = queries.findById(result.deliveryId())
                .orElseThrow(() -> new DeliveryNotFoundException(result.deliveryId()));

        return ResponseEntity
                .status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .location(URI.create("/internal/v1/deliveries/" + result.deliveryId()))
                .body(view);
    }

    /**
     * List deliveries, filtered by event id or by status.
     *
     * <p>With {@code status=FAILED_EXHAUSTED} this is the dead-letter view: the deliveries that
     * used their whole attempt budget and stopped. Having it be an ordinary query rather than a
     * separate store is one of the things the database-as-queue design buys -- a dead letter can be
     * joined to its own attempt history.
     *
     * @param includeAttempts only honoured for an {@code eventId} lookup, which returns at most one
     *                        delivery. A status listing never inlines histories, because fifty
     *                        deliveries each fetching their attempts is the N+1 this API avoids.
     */
    @GetMapping
    public DeliveryListView list(
            @RequestParam(required = false) String eventId,
            @RequestParam(required = false) DeliveryStatus status,
            @RequestParam(required = false, defaultValue = "false") boolean includeAttempts,
            @RequestParam(required = false, defaultValue = "" + DEFAULT_LIMIT) int limit) {

        int effectiveLimit = Math.clamp(limit, 1, MAX_LIMIT);

        List<DeliveryView> found;
        if (eventId != null) {
            found = queries.findByEventId(eventId, includeAttempts);
        } else if (status != null) {
            found = queries.findByStatus(status, effectiveLimit);
        } else {
            found = queries.findRecent(effectiveLimit);
        }
        return DeliveryListView.of(found);
    }

    /** One delivery, with its ordered attempt history. */
    @GetMapping("/{deliveryId}")
    public DeliveryView get(@PathVariable UUID deliveryId) {
        return queries.findById(deliveryId).orElseThrow(() -> new DeliveryNotFoundException(deliveryId));
    }

    /**
     * Just the history, oldest attempt first.
     *
     * <p>404 rather than an empty list when the delivery does not exist: "this delivery has made no
     * attempts yet" and "there is no such delivery" are different answers, and a reviewer checking
     * an id needs to be able to tell them apart.
     */
    @GetMapping("/{deliveryId}/attempts")
    public List<AttemptView> attempts(@PathVariable UUID deliveryId) {
        if (!queries.exists(deliveryId)) {
            throw new DeliveryNotFoundException(deliveryId);
        }
        return DeliveryViewMapper.toViews(queries.attemptsOf(deliveryId));
    }
}
