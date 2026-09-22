package com.caygnus.webhook.delivery.domain;

import static com.caygnus.webhook.common.model.DeliveryStatus.FAILED_EXHAUSTED;
import static com.caygnus.webhook.common.model.DeliveryStatus.FAILED_PERMANENT;
import static com.caygnus.webhook.common.model.DeliveryStatus.IN_FLIGHT;
import static com.caygnus.webhook.common.model.DeliveryStatus.PENDING;
import static com.caygnus.webhook.common.model.DeliveryStatus.RETRY_SCHEDULED;
import static com.caygnus.webhook.common.model.DeliveryStatus.SUCCEEDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caygnus.webhook.common.model.DeliveryStatus;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The whole 6x6 matrix, asserted against a table written out independently of the production one.
 *
 * <p>Restating the edges by hand is the point: a test that asked {@code DeliveryStateMachine} what
 * it allows and then checked it allows that would pass no matter what the table said.
 */
class DeliveryStateMachineTest {

    /** ARCHITECTURE.md 9.6, transcribed by hand. */
    private static final Map<DeliveryStatus, Set<DeliveryStatus>> EXPECTED_EDGES = Map.of(
            PENDING, EnumSet.of(IN_FLIGHT),
            RETRY_SCHEDULED, EnumSet.of(IN_FLIGHT),
            IN_FLIGHT, EnumSet.of(SUCCEEDED, FAILED_PERMANENT, FAILED_EXHAUSTED, RETRY_SCHEDULED),
            SUCCEEDED, EnumSet.noneOf(DeliveryStatus.class),
            FAILED_PERMANENT, EnumSet.noneOf(DeliveryStatus.class),
            FAILED_EXHAUSTED, EnumSet.noneOf(DeliveryStatus.class));

    private static Stream<Arguments> everyOrderedPair() {
        return Arrays.stream(DeliveryStatus.values())
                .flatMap(from -> Arrays.stream(DeliveryStatus.values()).map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("everyOrderedPair")
    void everyTransitionIsExactlyAsLegalAsTheDesignSays(DeliveryStatus from, DeliveryStatus to) {
        boolean expected = EXPECTED_EDGES.get(from).contains(to);

        assertThat(DeliveryStateMachine.isLegal(from, to)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("everyOrderedPair")
    void requireLegalThrowsForPreciselyTheIllegalPairs(DeliveryStatus from, DeliveryStatus to) {
        if (EXPECTED_EDGES.get(from).contains(to)) {
            DeliveryStateMachine.requireLegal(from, to); // must not throw
            return;
        }
        assertThatExceptionOfType(IllegalStateTransitionException.class)
                .isThrownBy(() -> DeliveryStateMachine.requireLegal(from, to))
                .satisfies(e -> {
                    assertThat(e.from()).isEqualTo(from);
                    assertThat(e.to()).isEqualTo(to);
                })
                .withMessageContaining(from.name())
                .withMessageContaining(to.name());
    }

    @ParameterizedTest
    @EnumSource(DeliveryStatus.class)
    void noStateMayTransitionToItself(DeliveryStatus status) {
        // A double claim must be a loud failure, not a second HTTP call.
        assertThat(DeliveryStateMachine.isLegal(status, status)).isFalse();
    }

    @Test
    void theThreeTerminalStatesAreTerminalAndNothingElseIs() {
        assertThat(Arrays.stream(DeliveryStatus.values()).filter(DeliveryStateMachine::isTerminal))
                .containsExactlyInAnyOrder(SUCCEEDED, FAILED_PERMANENT, FAILED_EXHAUSTED);
    }

    @Test
    void terminalStatesHaveNoWayBackIntoWork() {
        List.of(SUCCEEDED, FAILED_PERMANENT, FAILED_EXHAUSTED).forEach(terminal ->
                assertThat(DeliveryStateMachine.legalTargets(terminal)).isEmpty());
    }

    @Test
    void onlyPendingAndRetryScheduledAreClaimable() {
        assertThat(Arrays.stream(DeliveryStatus.values()).filter(DeliveryStateMachine::isClaimable))
                .containsExactlyInAnyOrder(PENDING, RETRY_SCHEDULED);
        assertThat(DeliveryStateMachine.claimableStates()).containsExactlyInAnyOrder(PENDING, RETRY_SCHEDULED);
    }

    @Test
    void noTerminalStateIsClaimable() {
        // The bound holds because the claim query cannot see a finished delivery, so this pair of
        // ideas must not be allowed to drift apart.
        assertThat(Arrays.stream(DeliveryStatus.values())
                .filter(DeliveryStateMachine::isTerminal)
                .filter(DeliveryStateMachine::isClaimable))
                .isEmpty();
    }

    @Test
    void everyStateAppearsInTheTable() {
        assertThat(Arrays.stream(DeliveryStatus.values()))
                .allSatisfy(status -> assertThat(DeliveryStateMachine.legalTargets(status)).isNotNull());
    }

    @Test
    void theTableCannotBeMutatedByACaller() {
        assertThatThrownBy(() -> DeliveryStateMachine.legalTargets(IN_FLIGHT).add(PENDING))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> DeliveryStateMachine.claimableStates().add(SUCCEEDED))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
