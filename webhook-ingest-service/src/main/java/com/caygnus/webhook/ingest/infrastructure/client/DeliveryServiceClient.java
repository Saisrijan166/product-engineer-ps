package com.caygnus.webhook.ingest.infrastructure.client;

import com.caygnus.webhook.common.api.DeliveryListView;
import com.caygnus.webhook.common.api.DeliveryView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The one call this service makes to the delivery service.
 *
 * <p>It reports what happened and never throws, for the same reason the delivery service's own
 * HTTP client does not: a 503 from a service that is restarting is an ordinary event on this path,
 * and turning it into an exception would only mean catching it again to decide the same thing.
 *
 * <p>The request carries {@code Idempotency-Key}, which is what makes the dispatcher safe to
 * retry. If it crashes between sending and recording that it sent, the repeat is recognised at
 * the far end and answered with the delivery that already exists -- so at-least-once dispatch
 * still produces exactly one delivery job.
 */
@Component
public class DeliveryServiceClient {

    /** Enough of an error body to diagnose from; the rest is not worth carrying into a log line. */
    private static final int MAX_ERROR_SNIPPET = 512;

    private static final Logger log = LoggerFactory.getLogger(DeliveryServiceClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    DeliveryServiceClient(RestClient deliveryServiceRestClient, ObjectMapper objectMapper) {
        this.restClient = deliveryServiceRestClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Ask the delivery service to schedule this event.
     *
     * @param eventId        sent as the idempotency key, and the same value the body carries
     * @param createRequest  the serialised {@code CreateDeliveryRequest}, straight from the outbox
     *                       row -- no reserialisation, so what was committed is what is sent
     */
    public DispatchOutcome dispatch(String eventId, String createRequest) {
        try {
            return restClient.post()
                    .uri("/internal/v1/deliveries")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", eventId)
                    .body(createRequest)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (response.getStatusCode().is2xxSuccessful()) {
                            // 201 created it, 200 means it already existed. Both mean the delivery
                            // service now holds exactly one delivery for this event, which is all
                            // the dispatcher needs to know.
                            return DispatchOutcome.accepted(readDeliveryId(response.getBody()));
                        }
                        String detail = "HTTP %d: %s".formatted(status, readSnippet(response.getBody()));
                        return response.getStatusCode().is5xxServerError()
                                ? DispatchOutcome.unavailable(detail)
                                : DispatchOutcome.rejected(detail);
                    });
        } catch (Exception unreachable) {
            log.warn("Could not reach the delivery service for eventId={}: {}",
                    eventId, unreachable.getMessage());
            return DispatchOutcome.unavailable(unreachable.toString());
        }
    }

    /**
     * Ask what became of an event, for the composed read.
     *
     * <p>{@code includeAttempts} means one round trip rather than two: the delivery and its whole
     * history come back together, which matters because this is the read AC5 is judged on.
     *
     * <p>Never throws. A composed read is mostly the ingest service's own data, and failing the
     * whole response because the other half could not be reached would make a degraded answer
     * into no answer -- see the graceful-degradation note on {@code EventQueryService}.
     */
    public DeliveryLookup lookupByEventId(String eventId) {
        try {
            return restClient.get()
                    .uri(builder -> builder.path("/internal/v1/deliveries")
                            .queryParam("eventId", eventId)
                            .queryParam("includeAttempts", true)
                            .build())
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            return DeliveryLookup.unavailable("HTTP %d from the delivery service"
                                    .formatted(response.getStatusCode().value()));
                        }
                        DeliveryListView found =
                                objectMapper.readValue(response.getBody(), DeliveryListView.class);
                        List<DeliveryView> deliveries = found.deliveries();
                        return deliveries == null || deliveries.isEmpty()
                                ? DeliveryLookup.absent()
                                : DeliveryLookup.found(deliveries.get(0));
                    });
        } catch (Exception unreachable) {
            log.warn("Could not look up the delivery for eventId={}: {}", eventId, unreachable.getMessage());
            return DeliveryLookup.unavailable(unreachable.toString());
        }
    }

    private UUID readDeliveryId(InputStream body) throws IOException {
        JsonNode acknowledgement = objectMapper.readTree(body);
        JsonNode deliveryId = acknowledgement.path("deliveryId");
        if (deliveryId.isMissingNode() || deliveryId.isNull()) {
            throw new IOException("The delivery service acknowledged without a deliveryId");
        }
        return UUID.fromString(deliveryId.asText());
    }

    private static String readSnippet(InputStream body) throws IOException {
        return new String(body.readNBytes(MAX_ERROR_SNIPPET), StandardCharsets.UTF_8);
    }
}
