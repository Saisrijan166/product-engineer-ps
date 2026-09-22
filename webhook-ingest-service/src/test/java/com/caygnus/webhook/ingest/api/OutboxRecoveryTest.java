package com.caygnus.webhook.ingest.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.caygnus.webhook.ingest.domain.DispatchStatus;
import com.caygnus.webhook.ingest.domain.OutboxDispatch;
import com.caygnus.webhook.ingest.support.AbstractIngestTest;
import com.caygnus.webhook.ingest.support.StubDeliveryService;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Zero loss when the delivery service is down.
 *
 * <p>This is what the transactional outbox is for, and the scenario that would break the obvious
 * implementation. Accept the event, commit, then call the delivery service: if that call fails,
 * the caller has a 201 for an event nobody will ever deliver, and nothing anywhere records that
 * it is owed. The event is lost while looking perfectly fine.
 *
 * <p>Here the intent commits with the event, so a delivery service that is down is an
 * inconvenience rather than a data-loss event -- the row waits, backs off, and goes out when the
 * far end returns. The dispatch schedule is the one the service ships with (1s, 3s, 9s...);
 * nothing sleeps through it, the clock is simply moved.
 */
class OutboxRecoveryTest extends AbstractIngestTest {

    private static final Duration FIRST_BACKOFF = Duration.ofSeconds(1);
    private static final Duration SECOND_BACKOFF = Duration.ofSeconds(3);

    @Test
    void anEventIsAcceptedEvenWhileTheDeliveryServiceIsDown() throws Exception {
        StubDeliveryService.unavailable();

        // The caller is unaffected. Refusing the event because a downstream service is restarting
        // would push the problem onto them, and they have nowhere to put it either.
        mockMvc.perform(postEvent("evt_123")).andExpect(status().isCreated());

        assertThat(events.count()).isEqualTo(1);
        assertThat(outbox.findByEventId("evt_123").orElseThrow().getStatus())
                .isEqualTo(DispatchStatus.PENDING);
    }

    @Test
    void aFailedDispatchLeavesTheIntentPendingWithABackoff() throws Exception {
        StubDeliveryService.unavailable();
        mockMvc.perform(postEvent("evt_123"));

        assertThat(dispatcher.runOnce()).isZero();

        OutboxDispatch intent = outbox.findByEventId("evt_123").orElseThrow();
        assertThat(intent.getStatus()).isEqualTo(DispatchStatus.PENDING);
        assertThat(intent.getAttempts()).isEqualTo(1);
        assertThat(intent.getDeliveryId()).isNull();
        assertThat(intent.getNextDispatchAt()).isEqualTo(START.plus(FIRST_BACKOFF));
        // Kept even though we will try again: a row that eventually gives up is no use to
        // whoever is looking at it if it does not say what kept going wrong.
        assertThat(intent.getLastError()).contains("503");
    }

    @Test
    void nothingIsRetriedBeforeItsTime() throws Exception {
        StubDeliveryService.unavailable();
        mockMvc.perform(postEvent("evt_123"));
        dispatcher.runOnce();

        assertThat(dispatcher.runOnce()).isZero();
        assertThat(StubDeliveryService.totalReceived())
                .as("a tick before the backoff elapsed must not call again")
                .isEqualTo(1);
    }

    @Test
    void theBackoffGrowsWhileTheServiceStaysDown() throws Exception {
        StubDeliveryService.unavailable();
        mockMvc.perform(postEvent("evt_123"));

        dispatcher.runOnce();
        assertThat(outbox.findByEventId("evt_123").orElseThrow().getNextDispatchAt())
                .isEqualTo(START.plus(FIRST_BACKOFF));

        CLOCK.advance(FIRST_BACKOFF);
        dispatcher.runOnce();
        assertThat(outbox.findByEventId("evt_123").orElseThrow().getNextDispatchAt())
                .isEqualTo(START.plus(FIRST_BACKOFF).plus(SECOND_BACKOFF));
    }

    /** The headline: the service comes back and the event goes out. */
    @Test
    void whenTheServiceReturnsTheNextTickDispatchesAndNothingWasLost() throws Exception {
        StubDeliveryService.unavailable();
        mockMvc.perform(postEvent("evt_123")).andExpect(status().isCreated());

        // Down for three ticks.
        dispatcher.runOnce();
        CLOCK.advance(FIRST_BACKOFF);
        dispatcher.runOnce();
        CLOCK.advance(SECOND_BACKOFF);
        dispatcher.runOnce();
        assertThat(outbox.findByEventId("evt_123").orElseThrow().getStatus())
                .isEqualTo(DispatchStatus.PENDING);

        StubDeliveryService.available();
        CLOCK.advance(Duration.ofSeconds(9));

        assertThat(dispatcher.runOnce()).isEqualTo(1);

        OutboxDispatch intent = outbox.findByEventId("evt_123").orElseThrow();
        assertThat(intent.getStatus()).isEqualTo(DispatchStatus.DISPATCHED);
        assertThat(intent.getDeliveryId()).isNotNull();
        assertThat(intent.getDispatchedAt()).isNotNull();
        assertThat(intent.getLastError()).isNull();
        // Four calls in total, all for the one event, and exactly one delivery at the far end.
        assertThat(StubDeliveryService.totalReceived()).isEqualTo(4);
    }

    @Test
    void theAcknowledgedDeliveryIdIsRecordedSoTheLoopCloses() throws Exception {
        UUID acknowledged = UUID.randomUUID();
        StubDeliveryService.acceptingAs(acknowledged);
        mockMvc.perform(postEvent("evt_123"));

        dispatcher.runOnce();

        // What makes the composed read possible in step 11: the ingest service now knows which
        // delivery belongs to this event rather than having to guess.
        assertThat(outbox.findByEventId("evt_123").orElseThrow().getDeliveryId()).isEqualTo(acknowledged);
    }

    @Test
    void everyDispatchCarriesTheIdempotencyKey() throws Exception {
        mockMvc.perform(postEvent("evt_123"));
        dispatcher.runOnce();

        // Why a repeated dispatch is safe, and therefore why the dispatcher can be at-least-once.
        assertThat(StubDeliveryService.received()).singleElement().satisfies(request -> {
            assertThat(request).containsEntry("idempotencyKey", "evt_123");
            assertThat(request.get("contentType")).contains("application/json");
            assertThat(request.get("body")).contains("evt_123").contains("inc_456");
        });
    }

    @Test
    void aDispatchedIntentIsNeverSentAgain() throws Exception {
        mockMvc.perform(postEvent("evt_123"));
        assertThat(dispatcher.runOnce()).isEqualTo(1);

        CLOCK.advance(Duration.ofHours(1));
        assertThat(dispatcher.runOnce()).isZero();
        assertThat(dispatcher.runOnce()).isZero();

        assertThat(StubDeliveryService.totalReceived()).isEqualTo(1);
    }

    @Test
    void theDispatcherGivesUpAfterItsOwnBoundAndSaysWhy() throws Exception {
        StubDeliveryService.unavailable();
        mockMvc.perform(postEvent("evt_123"));

        for (int attempt = 1; attempt <= 10; attempt++) {
            dispatcher.runOnce();
            CLOCK.advance(Duration.ofMinutes(10));
        }

        OutboxDispatch intent = outbox.findByEventId("evt_123").orElseThrow();
        assertThat(intent.getStatus()).isEqualTo(DispatchStatus.FAILED);
        assertThat(intent.getAttempts()).isEqualTo(10);
        assertThat(intent.getLastError()).contains("503");
        // The event itself is still there. Giving up on reaching the delivery service is not the
        // same as discarding what a caller was told we had accepted.
        assertThat(events.findByEventId("evt_123")).isPresent();

        assertThat(dispatcher.runOnce()).isZero();
        assertThat(StubDeliveryService.totalReceived()).isEqualTo(10);
    }

    @Test
    void anOutrightRejectionIsNotRetriedTenTimes() throws Exception {
        StubDeliveryService.rejecting(400);
        mockMvc.perform(postEvent("evt_123"));

        assertThat(dispatcher.runOnce()).isZero();

        // A 400 means we sent something it will never accept. Nine more identical requests would
        // only delay anyone noticing.
        OutboxDispatch intent = outbox.findByEventId("evt_123").orElseThrow();
        assertThat(intent.getStatus()).isEqualTo(DispatchStatus.FAILED);
        assertThat(intent.getAttempts()).isEqualTo(1);
        assertThat(intent.getLastError()).contains("400");

        CLOCK.advance(Duration.ofHours(1));
        assertThat(dispatcher.runOnce()).isZero();
        assertThat(StubDeliveryService.totalReceived()).isEqualTo(1);
    }

    @Test
    void aConnectionRefusedIsTreatedAsTheServiceBeingDown() throws Exception {
        mockMvc.perform(postEvent("evt_123"));

        // Nothing listening at all, which is what a restart looks like for a moment.
        StubDeliveryService.stop();
        try {
            assertThat(dispatcher.runOnce()).isZero();

            OutboxDispatch intent = outbox.findByEventId("evt_123").orElseThrow();
            assertThat(intent.getStatus()).isEqualTo(DispatchStatus.PENDING);
            assertThat(intent.getAttempts()).isEqualTo(1);
            assertThat(intent.getLastError()).isNotBlank();
        } finally {
            StubDeliveryService.restart();
        }
    }

    @Test
    void oneStuckEventDoesNotHoldUpTheRest() throws Exception {
        StubDeliveryService.unavailable();
        mockMvc.perform(postEvent("evt_stuck"));
        dispatcher.runOnce();

        StubDeliveryService.available();
        mockMvc.perform(postEvent("evt_fine"));
        CLOCK.advance(FIRST_BACKOFF);

        assertThat(dispatcher.runOnce()).isEqualTo(2);
        assertThat(outbox.findByStatus(DispatchStatus.DISPATCHED)).hasSize(2);
    }

    @Test
    void aBatchIsBoundedSoOneTickCannotHoldTheWholeTableOpen() throws Exception {
        for (int i = 1; i <= 25; i++) {
            mockMvc.perform(postEvent("evt_" + i));
        }

        // batch-size is 20, and that number is also the bound on how long one tick can hold a
        // transaction across HTTP -- see the comment on OutboxDispatcher.
        assertThat(dispatcher.runOnce()).isEqualTo(20);
        assertThat(dispatcher.runOnce()).isEqualTo(5);
    }

    @Test
    void theDispatchStateIsDurableRatherThanAnArtefactOfTheProcess() throws Exception {
        mockMvc.perform(postEvent("evt_123"));
        dispatcher.runOnce();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, attempts, delivery_id, dispatched_at FROM outbox_dispatch WHERE event_id = ?",
                "evt_123");
        assertThat(row).containsEntry("status", "DISPATCHED").containsEntry("attempts", 0);
        assertThat(row.get("delivery_id")).isNotNull();
        assertThat(row.get("dispatched_at")).isNotNull();
    }
}
