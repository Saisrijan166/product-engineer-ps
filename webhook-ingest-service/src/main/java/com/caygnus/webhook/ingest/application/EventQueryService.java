package com.caygnus.webhook.ingest.application;

import com.caygnus.webhook.ingest.api.EventNotFoundException;
import com.caygnus.webhook.ingest.api.dto.DispatchView;
import com.caygnus.webhook.ingest.api.dto.EventDetailView;
import com.caygnus.webhook.ingest.api.dto.EventPageView;
import com.caygnus.webhook.ingest.api.dto.EventSummaryView;
import com.caygnus.webhook.ingest.domain.OutboxDispatch;
import com.caygnus.webhook.ingest.domain.WebhookEvent;
import com.caygnus.webhook.ingest.infrastructure.client.DeliveryLookup;
import com.caygnus.webhook.ingest.infrastructure.client.DeliveryServiceClient;
import com.caygnus.webhook.ingest.infrastructure.persistence.OutboxDispatchRepository;
import com.caygnus.webhook.ingest.infrastructure.persistence.WebhookEventRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads over accepted events, including the one that spans both services.
 *
 * <p><b>Graceful degradation (ARCHITECTURE.md §3.2).</b> The composed read is mostly this
 * service's own data -- what was submitted, when, how many times, and how the handoff went -- and
 * all of that is available whatever the delivery service is doing. Failing the whole response
 * because the other half could not be reached would turn a partial answer into no answer, and
 * would do it at exactly the moment someone is most likely to be asking: during an incident.
 *
 * <p>So a failed lookup is reported rather than thrown. The response stays a 200 with
 * {@code delivery: null} and a {@code deliveryLookupError} saying why, which is honest about what
 * is missing without pretending the event itself is unknown.
 *
 * <p>The distinction that needs care is between "no delivery yet" and "could not ask". Both leave
 * {@code delivery} null, and they mean opposite things -- the first is the ordinary state of an
 * event whose dispatch is still pending, the second is a degraded read. Only the second sets the
 * error field.
 */
@Service
@Transactional(readOnly = true)
public class EventQueryService {

    private final WebhookEventRepository events;
    private final OutboxDispatchRepository outbox;
    private final DeliveryServiceClient deliveryService;

    EventQueryService(
            WebhookEventRepository events,
            OutboxDispatchRepository outbox,
            DeliveryServiceClient deliveryService) {
        this.events = events;
        this.outbox = outbox;
        this.deliveryService = deliveryService;
    }

    /**
     * One event, its dispatch, and its delivery with the whole ordered attempt history.
     *
     * <p>The delivery service is asked only when there is a dispatch to ask about -- an event
     * whose outbox row is still pending cannot have a delivery yet, and calling to be told so
     * would spend a round trip and a timeout budget to learn nothing.
     *
     * @throws EventNotFoundException if no event was ever accepted under this identifier
     */
    public EventDetailView findByEventId(String eventId) {
        WebhookEvent event = events.findByEventId(eventId)
                .orElseThrow(() -> new EventNotFoundException(eventId));

        OutboxDispatch dispatch = outbox.findByEventId(eventId).orElse(null);
        DispatchView dispatchView = dispatch == null ? null : DispatchView.of(dispatch);

        if (dispatch == null || dispatch.getDeliveryId() == null) {
            return EventDetailView.of(event, dispatchView, null, null);
        }

        DeliveryLookup lookup = deliveryService.lookupByEventId(eventId);
        return EventDetailView.of(event, dispatchView, lookup.delivery(), lookup.error());
    }

    public EventPageView list(int page, int size) {
        Page<EventSummaryView> found = events
                .findAllByOrderByReceivedAtDesc(PageRequest.of(page, size))
                .map(EventSummaryView::of);
        return new EventPageView(found.getContent(), page, size, found.getTotalElements());
    }
}
