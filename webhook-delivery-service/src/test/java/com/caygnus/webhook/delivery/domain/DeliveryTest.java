package com.caygnus.webhook.delivery.domain;

import static com.caygnus.webhook.common.model.DeliveryStatus.FAILED_EXHAUSTED;
import static com.caygnus.webhook.common.model.DeliveryStatus.FAILED_PERMANENT;
import static com.caygnus.webhook.common.model.DeliveryStatus.IN_FLIGHT;
import static com.caygnus.webhook.common.model.DeliveryStatus.PENDING;
import static com.caygnus.webhook.common.model.DeliveryStatus.RETRY_SCHEDULED;
import static com.caygnus.webhook.common.model.DeliveryStatus.SUCCEEDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.caygnus.webhook.common.model.AttemptOutcome;
import com.caygnus.webhook.common.model.TerminalReason;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** The guards that the state machine alone cannot express: the attempt budget and the lease. */
class DeliveryTest {

    private static final Instant NOW = Instant.parse("2026-09-15T10:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(15);
    private static final String WORKER = "delivery-1:7:a3f";
    private static final int MAX_ATTEMPTS = 5;

    private Delivery newDelivery() {
        return Delivery.create(
                "evt_123",
                "incident.created",
                OffsetDateTime.parse("2026-09-15T09:59:00Z"),
                "{\"incidentId\":\"inc_456\"}",
                "http://receiver:8082/receive",
                MAX_ATTEMPTS,
                NOW);
    }

    @Test
    void startsPendingAndDueImmediately() {
        Delivery delivery = newDelivery();

        assertThat(delivery.getStatus()).isEqualTo(PENDING);
        assertThat(delivery.getAttemptCount()).isZero();
        assertThat(delivery.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(delivery.getId()).isNotNull();
        assertThat(delivery.isTerminal()).isFalse();
    }

    @Test
    void snapshotsTheTargetAndBudgetSoHistoryStaysReadableAfterConfigurationChanges() {
        Delivery delivery = newDelivery();

        assertThat(delivery.getTargetUrl()).isEqualTo("http://receiver:8082/receive");
        assertThat(delivery.getMaxAttempts()).isEqualTo(MAX_ATTEMPTS);
    }

    @Test
    void claimSpendsAnAttemptBeforeTheCallGoesOut() {
        Delivery delivery = newDelivery();

        delivery.claim(WORKER, NOW, LEASE);

        // The increment happens here, not after the response: a process that dies mid-flight has
        // already spent the attempt, so the bound survives the crash.
        assertThat(delivery.getStatus()).isEqualTo(IN_FLIGHT);
        assertThat(delivery.getAttemptCount()).isEqualTo(1);
        assertThat(delivery.getLeaseOwner()).isEqualTo(WORKER);
        assertThat(delivery.getLeaseExpiresAt()).isEqualTo(NOW.plus(LEASE));
        assertThat(delivery.getFirstAttemptAt()).isEqualTo(NOW);
        assertThat(delivery.getNextAttemptAt()).isNull();
    }

    @Test
    void firstAttemptAtIsSetOnceAndNotMovedByLaterClaims() {
        Delivery delivery = newDelivery();
        delivery.claim(WORKER, NOW, LEASE);
        delivery.recordRetryableFailure(NOW, NOW.plusSeconds(5));

        Instant later = NOW.plusSeconds(5);
        delivery.claim(WORKER, later, LEASE);

        assertThat(delivery.getFirstAttemptAt()).isEqualTo(NOW);
    }

    @Test
    void aDeliveryAlreadyInFlightCannotBeClaimedAgain() {
        Delivery delivery = newDelivery();
        delivery.claim(WORKER, NOW, LEASE);

        assertThatExceptionOfType(IllegalStateTransitionException.class)
                .isThrownBy(() -> delivery.claim("delivery-2:9:b1c", NOW, LEASE));
        assertThat(delivery.getAttemptCount()).isEqualTo(1);
    }

    @Test
    void successIsTerminalAndClearsTheLease() {
        Delivery delivery = newDelivery();
        delivery.claim(WORKER, NOW, LEASE);

        delivery.recordSuccess(NOW.plusMillis(120));

        assertThat(delivery.getStatus()).isEqualTo(SUCCEEDED);
        assertThat(delivery.getLastOutcome()).isEqualTo(AttemptOutcome.SUCCESS);
        assertThat(delivery.getCompletedAt()).isEqualTo(NOW.plusMillis(120));
        assertThat(delivery.getTerminalReason()).isNull();
        assertThat(delivery.getLeaseOwner()).isNull();
        assertThat(delivery.getNextAttemptAt()).isNull();
        assertThat(delivery.isTerminal()).isTrue();
    }

    @Test
    void aPermanentFailureStopsImmediatelyWithTheBudgetUnspent() {
        Delivery delivery = newDelivery();
        delivery.claim(WORKER, NOW, LEASE);

        delivery.recordPermanentFailure(NOW.plusMillis(40));

        assertThat(delivery.getStatus()).isEqualTo(FAILED_PERMANENT);
        assertThat(delivery.getTerminalReason()).isEqualTo(TerminalReason.NON_RETRYABLE_RESPONSE);
        assertThat(delivery.getAttemptCount()).isEqualTo(1);
        assertThat(delivery.getNextAttemptAt()).isNull();
    }

    @Test
    void aRetryableFailureBelowTheBudgetSchedulesAnotherAttempt() {
        Delivery delivery = newDelivery();
        delivery.claim(WORKER, NOW, LEASE);
        Instant due = NOW.plusSeconds(5);

        delivery.recordRetryableFailure(NOW, due);

        assertThat(delivery.getStatus()).isEqualTo(RETRY_SCHEDULED);
        assertThat(delivery.getNextAttemptAt()).isEqualTo(due);
        assertThat(delivery.getLastOutcome()).isEqualTo(AttemptOutcome.RETRYABLE_FAILURE);
        assertThat(delivery.getLeaseOwner()).isNull();
        assertThat(delivery.getCompletedAt()).isNull();
    }

    @Test
    void aRetryableFailureOnTheLastAttemptExhaustsRatherThanRescheduling() {
        Delivery delivery = failEveryAttemptUpTo(MAX_ATTEMPTS);

        // The decision lives here and nowhere else, so computing a backoff for a sixth attempt
        // cannot produce one.
        assertThat(delivery.getStatus()).isEqualTo(FAILED_EXHAUSTED).isNotEqualTo(RETRY_SCHEDULED);
        assertThat(delivery.getTerminalReason()).isEqualTo(TerminalReason.ATTEMPTS_EXHAUSTED);
        assertThat(delivery.getAttemptCount()).isEqualTo(MAX_ATTEMPTS);
        assertThat(delivery.getNextAttemptAt()).isNull();
        assertThat(delivery.getCompletedAt()).isEqualTo(NOW);
    }

    @Test
    void theWhenDueArgumentIsIgnoredOnTheLastAttempt() {
        Delivery delivery = failEveryAttemptUpTo(MAX_ATTEMPTS);

        assertThat(delivery.getNextAttemptAt()).isNull();
        assertThat(delivery.hasAttemptsRemaining()).isFalse();
    }

    @Test
    void anExhaustedDeliveryCannotBeClaimedAgain() {
        Delivery delivery = failEveryAttemptUpTo(MAX_ATTEMPTS);

        // Belt and braces: the claim query cannot see a terminal delivery, and it would refuse
        // anyway -- as an illegal transition, since being finished is the more fundamental problem
        // than the budget being spent.
        assertThatExceptionOfType(IllegalStateTransitionException.class)
                .isThrownBy(() -> delivery.claim(WORKER, NOW.plusSeconds(600), LEASE));
        assertThat(delivery.getAttemptCount()).isEqualTo(MAX_ATTEMPTS);
        assertThat(delivery.getLeaseOwner()).isNull();
    }

    @Test
    void aRefusedOutcomeLeavesTheDeliveryExactlyAsItWas() {
        Delivery delivery = succeeded();

        assertThatExceptionOfType(IllegalStateTransitionException.class)
                .isThrownBy(() -> delivery.recordRetryableFailure(NOW, NOW.plusSeconds(5)));

        // Nothing half-written: a rejected transition must not leave a SUCCEEDED delivery claiming
        // its last outcome was a failure.
        assertThat(delivery.getStatus()).isEqualTo(SUCCEEDED);
        assertThat(delivery.getLastOutcome()).isEqualTo(AttemptOutcome.SUCCESS);
        assertThat(delivery.getTerminalReason()).isNull();
        assertThat(delivery.getNextAttemptAt()).isNull();
    }

    @Test
    void aBudgetOfOneMeansTheFirstRetryableFailureIsTerminal() {
        Delivery single = Delivery.create("evt_1", "t", null, "{}", "http://receiver/receive", 1, NOW);
        single.claim(WORKER, NOW, LEASE);

        single.recordRetryableFailure(NOW, NOW.plusSeconds(5));

        assertThat(single.getStatus()).isEqualTo(FAILED_EXHAUSTED);
    }

    @Test
    void reapingAnExpiredLeaseRequeuesWithoutRefundingTheAttempt() {
        Delivery delivery = newDelivery();
        delivery.claim(WORKER, NOW, LEASE);
        Instant afterLease = NOW.plus(LEASE).plusSeconds(1);

        delivery.reapExpiredLease(afterLease);

        // The call may well have reached the receiver; counting it is what keeps the bound honest.
        assertThat(delivery.getStatus()).isEqualTo(RETRY_SCHEDULED);
        assertThat(delivery.getAttemptCount()).isEqualTo(1);
        assertThat(delivery.getNextAttemptAt()).isEqualTo(afterLease);
        assertThat(delivery.getLeaseOwner()).isNull();
    }

    @Test
    void reapingOnTheLastAttemptFinishesRatherThanRequeuing() {
        Delivery delivery = failEveryAttemptUpTo(MAX_ATTEMPTS - 1);
        delivery.claim(WORKER, NOW, LEASE);
        Instant afterLease = NOW.plus(LEASE).plusSeconds(1);

        delivery.reapExpiredLease(afterLease);

        assertThat(delivery.getStatus()).isEqualTo(FAILED_EXHAUSTED);
        assertThat(delivery.getTerminalReason()).isEqualTo(TerminalReason.ATTEMPTS_EXHAUSTED);
    }

    @Test
    void aLiveLeaseIsNotReapable() {
        Delivery delivery = newDelivery();
        delivery.claim(WORKER, NOW, LEASE);

        assertThatIllegalStateException()
                .isThrownBy(() -> delivery.reapExpiredLease(NOW.plusSeconds(1)))
                .withMessageContaining("has not expired");
        assertThat(delivery.getStatus()).isEqualTo(IN_FLIGHT);
        assertThat(delivery.isLeaseExpired(NOW.plusSeconds(1))).isFalse();
        assertThat(delivery.isLeaseExpired(NOW.plus(LEASE))).isTrue();
    }

    @Test
    void aDeliveryThatWasNeverClaimedHasNoLeaseToReap() {
        assertThatIllegalStateException().isThrownBy(() -> newDelivery().reapExpiredLease(NOW.plusSeconds(60)));
    }

    @Test
    void terminalDeliveriesRefuseEveryOutcome() {
        assertThatExceptionOfType(IllegalStateTransitionException.class)
                .isThrownBy(() -> succeeded().recordSuccess(NOW));
        assertThatExceptionOfType(IllegalStateTransitionException.class)
                .isThrownBy(() -> succeeded().recordRetryableFailure(NOW, NOW.plusSeconds(5)));
        assertThatExceptionOfType(IllegalStateTransitionException.class)
                .isThrownBy(() -> succeeded().recordPermanentFailure(NOW));
    }

    @Test
    void anUnclaimedDeliveryCannotRecordAnOutcome() {
        assertThatExceptionOfType(IllegalStateTransitionException.class)
                .isThrownBy(() -> newDelivery().recordSuccess(NOW));
    }

    @Test
    void rejectsABudgetBelowOne() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Delivery.create("evt_1", "t", null, "{}", "http://receiver/receive", 0, NOW))
                .withMessageContaining("maxAttempts");
    }

    @Test
    void exposesNoSetterThatCouldBypassTheStateMachine() {
        // ARCHITECTURE.md invariant 10, asserted rather than trusted to review.
        assertThat(Arrays.stream(Delivery.class.getMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .map(Method::getName)
                .filter(name -> name.startsWith("set")))
                .isEmpty();
    }

    private Delivery succeeded() {
        Delivery delivery = newDelivery();
        delivery.claim(WORKER, NOW, LEASE);
        delivery.recordSuccess(NOW);
        return delivery;
    }

    /** Claim and fail retryably {@code attempts} times, leaving the delivery wherever that lands it. */
    private Delivery failEveryAttemptUpTo(int attempts) {
        Delivery delivery = newDelivery();
        for (int i = 0; i < attempts; i++) {
            delivery.claim(WORKER, NOW, LEASE);
            delivery.recordRetryableFailure(NOW, NOW.plusSeconds(5L * (i + 1)));
        }
        return delivery;
    }
}
