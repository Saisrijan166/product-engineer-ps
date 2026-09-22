package com.caygnus.webhook.delivery.domain;

import com.caygnus.webhook.common.model.DeliveryStatus;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The delivery lifecycle, as data.
 *
 * <p>Every state change in the service is checked against this one table, so the set of things
 * that can happen to a delivery is a page you can read rather than a control flow you have to
 * trace. Anything not in the table throws.
 *
 * <p>Two properties are worth stating because the rest of the design leans on them:
 *
 * <ul>
 *   <li><b>Terminal states have no outgoing edges.</b> The scheduler's claim query selects only
 *       {@link #isClaimable claimable} states, so a finished delivery is invisible to it. The
 *       attempt bound therefore holds structurally, not because some counter is checked carefully
 *       enough in every branch.
 *   <li><b>There are no self-transitions.</b> {@code IN_FLIGHT -> IN_FLIGHT} being illegal is what
 *       makes a double claim a loud failure instead of a second HTTP call.
 * </ul>
 *
 * <p>The guards in ARCHITECTURE.md 9.6 that depend on a delivery's own fields -- the attempt budget,
 * whether a lease has expired -- live in {@link Delivery}, because this table only knows about
 * states. The one edge deliberately missing is terminal to {@code RETRY_SCHEDULED}: replay is
 * optional, and if it is built it gets its own explicit method rather than a hole in the table.
 */
public final class DeliveryStateMachine {

    private static final Map<DeliveryStatus, Set<DeliveryStatus>> ALLOWED = allowedTransitions();

    /** States the scheduler may claim. Kept here so the claim query and this table cannot drift. */
    private static final Set<DeliveryStatus> CLAIMABLE =
            Collections.unmodifiableSet(EnumSet.of(DeliveryStatus.PENDING, DeliveryStatus.RETRY_SCHEDULED));

    private DeliveryStateMachine() {
    }

    private static Map<DeliveryStatus, Set<DeliveryStatus>> allowedTransitions() {
        EnumMap<DeliveryStatus, Set<DeliveryStatus>> transitions = new EnumMap<>(DeliveryStatus.class);

        transitions.put(DeliveryStatus.PENDING, EnumSet.of(DeliveryStatus.IN_FLIGHT));
        transitions.put(DeliveryStatus.RETRY_SCHEDULED, EnumSet.of(DeliveryStatus.IN_FLIGHT));
        transitions.put(DeliveryStatus.IN_FLIGHT, EnumSet.of(
                DeliveryStatus.SUCCEEDED,
                DeliveryStatus.FAILED_PERMANENT,
                DeliveryStatus.FAILED_EXHAUSTED,
                DeliveryStatus.RETRY_SCHEDULED));
        transitions.put(DeliveryStatus.SUCCEEDED, EnumSet.noneOf(DeliveryStatus.class));
        transitions.put(DeliveryStatus.FAILED_PERMANENT, EnumSet.noneOf(DeliveryStatus.class));
        transitions.put(DeliveryStatus.FAILED_EXHAUSTED, EnumSet.noneOf(DeliveryStatus.class));

        transitions.replaceAll((state, targets) -> Collections.unmodifiableSet(targets));
        return Collections.unmodifiableMap(transitions);
    }

    public static boolean isLegal(DeliveryStatus from, DeliveryStatus to) {
        return legalTargets(from).contains(to);
    }

    /**
     * @throws IllegalStateTransitionException if the table has no such edge
     */
    public static void requireLegal(DeliveryStatus from, DeliveryStatus to) {
        if (!isLegal(from, to)) {
            throw new IllegalStateTransitionException(from, to);
        }
    }

    public static Set<DeliveryStatus> legalTargets(DeliveryStatus from) {
        return ALLOWED.get(from);
    }

    /** No outgoing edges: the delivery is finished and can never be attempted again. */
    public static boolean isTerminal(DeliveryStatus status) {
        return legalTargets(status).isEmpty();
    }

    /** Due work, from the scheduler's point of view. The time check is the query's job. */
    public static boolean isClaimable(DeliveryStatus status) {
        return CLAIMABLE.contains(status);
    }

    public static Set<DeliveryStatus> claimableStates() {
        return CLAIMABLE;
    }
}
