package com.caygnus.webhook.delivery.domain;

import com.caygnus.webhook.common.model.AttemptOutcome;
import com.caygnus.webhook.common.model.DeliveryStatus;
import com.caygnus.webhook.common.model.TerminalReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One logical delivery job, and the only thing allowed to change its own state.
 *
 * <p>There is no {@code setStatus}. Callers say what happened -- it was claimed, the receiver said
 * yes, the receiver said no, the lease ran out -- and the delivery works out what that means. That
 * is what keeps the guards from ARCHITECTURE.md 9.6 in one readable place instead of being
 * re-derived at each call site, and it is why "retryable failure on the last attempt" cannot
 * accidentally schedule a sixth attempt.
 *
 * <p>Two invariants are carried here rather than in the database:
 *
 * <ul>
 *   <li><b>The attempt count rises at claim time</b>, before the HTTP call. A process that dies
 *       mid-flight has already spent the attempt, so the bound survives a crash instead of
 *       resetting with it.
 *   <li><b>A terminal delivery stays terminal.</b> {@link DeliveryStateMachine} has no outgoing
 *       edges from a terminal state, so every route back into work is closed at once.
 * </ul>
 *
 * <p>The persistence mapping was added after the state rules were proven, and adds nothing to
 * them: every annotation here describes storage, never behaviour.
 */
@Entity
@Table(name = "delivery")
public class Delivery {

    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private String eventId;

    @Column(name = "event_type", updatable = false)
    private String eventType;

    @Column(name = "occurred_at", updatable = false)
    private OffsetDateTime occurredAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", updatable = false)
    private String payload;

    @Column(name = "target_url", nullable = false, updatable = false)
    private String targetUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private DeliveryStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "max_attempts", nullable = false, updatable = false)
    private int maxAttempts;

    /** When the next attempt is due. Non-null exactly while the delivery is waiting to be claimed. */
    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "lease_owner", length = 64)
    private String leaseOwner;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "terminal_reason", length = 32)
    private TerminalReason terminalReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_outcome", length = 24)
    private AttemptOutcome lastOutcome;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "first_attempt_at")
    private Instant firstAttemptAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    /**
     * Guards the read-modify-write in the outcome transaction. The claim already holds a row lock
     * for its own transaction, but the reaper and a returning worker can still meet on the same row
     * afterwards; this is what makes one of them lose loudly instead of silently overwriting.
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /**
     * Read-only, lazy, and not how history is read: {@code DeliveryAttemptRepository} is, because a
     * collection that loads itself the moment something touches it is how an N+1 gets in. This
     * mapping exists to express that attempts belong to a delivery and nothing else.
     */
    @OneToMany(fetch = FetchType.LAZY)
    @JoinColumn(name = "delivery_id", insertable = false, updatable = false)
    @OrderBy("attemptNumber ASC")
    private List<DeliveryAttempt> attempts = new ArrayList<>();

    protected Delivery() {
        // For Hibernate and for the static factory below.
    }

    /**
     * A new delivery, due immediately.
     *
     * <p>{@code maxAttempts} and {@code targetUrl} are snapshotted rather than read from
     * configuration at attempt time, so the history stays readable after either one changes.
     */
    public static Delivery create(
            String eventId,
            String eventType,
            OffsetDateTime occurredAt,
            String payload,
            String targetUrl,
            int maxAttempts,
            Instant now) {

        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1, got " + maxAttempts);
        }
        Delivery delivery = new Delivery();
        delivery.id = UUID.randomUUID();
        delivery.eventId = Objects.requireNonNull(eventId, "eventId");
        delivery.eventType = eventType;
        delivery.occurredAt = occurredAt;
        delivery.payload = payload;
        delivery.targetUrl = Objects.requireNonNull(targetUrl, "targetUrl");
        delivery.status = DeliveryStatus.PENDING;
        delivery.attemptCount = 0;
        delivery.maxAttempts = maxAttempts;
        delivery.nextAttemptAt = now;
        delivery.createdAt = now;
        return delivery;
    }

    /**
     * Take ownership for one attempt: spend an attempt from the budget and hold a lease for as long
     * as the attempt could reasonably take.
     *
     * <p>The lease is what makes a crash recoverable. It is set here, before the call goes out, so
     * a delivery interrupted at any point is discoverable as in-flight-and-overdue rather than
     * merely stuck.
     *
     * <p>Legality is checked before anything is written, so a refused claim leaves the delivery
     * exactly as it was. The budget guard behind it is defensive rather than reachable: every path
     * that spends the last attempt ends terminal, so no claimable delivery can have an empty
     * budget. It is here to catch an inconsistent row rather than a caller mistake.
     *
     * @throws IllegalStateTransitionException  if this delivery is not claimable
     * @throws IllegalStateException            if a claimable delivery has no attempts left
     */
    public void claim(String leaseOwner, Instant now, Duration leaseDuration) {
        DeliveryStateMachine.requireLegal(status, DeliveryStatus.IN_FLIGHT);
        if (!hasAttemptsRemaining()) {
            throw new IllegalStateException(
                    "Delivery %s has spent its %d attempts and must not be claimed".formatted(id, maxAttempts));
        }
        transitionTo(DeliveryStatus.IN_FLIGHT);
        attemptCount++;
        this.leaseOwner = Objects.requireNonNull(leaseOwner, "leaseOwner");
        this.leaseExpiresAt = now.plus(leaseDuration);
        this.nextAttemptAt = null;
        if (firstAttemptAt == null) {
            firstAttemptAt = now;
        }
    }

    /** The receiver returned 2xx. Done. */
    public void recordSuccess(Instant now) {
        transitionTo(DeliveryStatus.SUCCEEDED);
        lastOutcome = AttemptOutcome.SUCCESS;
        completedAt = now;
        releaseLease();
        nextAttemptAt = null;
    }

    /**
     * The receiver returned something no retry will fix. Stops immediately, with the attempt budget
     * left unspent -- which is the whole point of classifying rather than retrying everything.
     */
    public void recordPermanentFailure(Instant now) {
        transitionTo(DeliveryStatus.FAILED_PERMANENT);
        lastOutcome = AttemptOutcome.PERMANENT_FAILURE;
        terminalReason = TerminalReason.NON_RETRYABLE_RESPONSE;
        completedAt = now;
        releaseLease();
        nextAttemptAt = null;
    }

    /**
     * The attempt failed in a way that might not fail next time.
     *
     * <p>Whether that means another attempt is decided here and nowhere else. On the last attempt
     * the delivery goes terminal and {@code whenDue} is ignored, so a caller cannot schedule a
     * sixth attempt by computing a backoff for one.
     *
     * @param whenDue when the next attempt should become due, from {@link BackoffPolicy}
     */
    public void recordRetryableFailure(Instant now, Instant whenDue) {
        if (hasAttemptsRemaining()) {
            Objects.requireNonNull(whenDue, "whenDue");
            transitionTo(DeliveryStatus.RETRY_SCHEDULED);
            nextAttemptAt = whenDue;
            releaseLease();
        } else {
            exhaust(now);
        }
        // Last, so that a refused outcome leaves the delivery exactly as it was.
        lastOutcome = AttemptOutcome.RETRYABLE_FAILURE;
    }

    /**
     * The worker holding this delivery stopped reporting and its lease has expired.
     *
     * <p>Reclaiming does not refund the attempt: the call may well have reached the receiver, and
     * counting it is what keeps the bound honest across a crash. A delivery whose budget ran out
     * while in flight is finished here rather than re-queued into a state it could never leave.
     *
     * @throws IllegalStateException if the lease has not actually expired
     */
    public void reapExpiredLease(Instant now) {
        if (leaseExpiresAt == null || leaseExpiresAt.isAfter(now)) {
            throw new IllegalStateException(
                    "Delivery %s holds a lease until %s, which has not expired at %s"
                            .formatted(id, leaseExpiresAt, now));
        }
        if (hasAttemptsRemaining()) {
            transitionTo(DeliveryStatus.RETRY_SCHEDULED);
            nextAttemptAt = now;
            releaseLease();
        } else {
            exhaust(now);
        }
    }

    /**
     * Undo a claim whose attempt never started, refunding the attempt.
     *
     * <p>The one place an attempt is ever given back, and only valid before any request has been
     * made -- the executor rejected the task for want of capacity. Everywhere else a spent attempt
     * stays spent, because once a request may have reached the receiver, refunding it would let
     * the bound be exceeded. The name is long on purpose.
     *
     * @throws IllegalStateException if this delivery was never claimed
     */
    public void releaseUnstartedClaim(Instant now) {
        if (status != DeliveryStatus.IN_FLIGHT) {
            throw new IllegalStateException(
                    "Delivery %s is %s, so there is no unstarted claim to release".formatted(id, status));
        }
        transitionTo(DeliveryStatus.RETRY_SCHEDULED);
        attemptCount--;
        nextAttemptAt = now;
        releaseLease();
    }

    private void exhaust(Instant now) {
        transitionTo(DeliveryStatus.FAILED_EXHAUSTED);
        terminalReason = TerminalReason.ATTEMPTS_EXHAUSTED;
        completedAt = now;
        releaseLease();
        nextAttemptAt = null;
    }

    /** The single gate. Every state change in this class goes through it; nothing else may. */
    private void transitionTo(DeliveryStatus target) {
        DeliveryStateMachine.requireLegal(status, target);
        this.status = target;
    }

    private void releaseLease() {
        leaseOwner = null;
        leaseExpiresAt = null;
    }

    public boolean hasAttemptsRemaining() {
        return attemptCount < maxAttempts;
    }

    public boolean isTerminal() {
        return DeliveryStateMachine.isTerminal(status);
    }

    public boolean isLeaseExpired(Instant now) {
        return leaseExpiresAt != null && !leaseExpiresAt.isAfter(now);
    }

    public UUID getId() {
        return id;
    }

    public String getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public OffsetDateTime getOccurredAt() {
        return occurredAt;
    }

    public String getPayload() {
        return payload;
    }

    public String getTargetUrl() {
        return targetUrl;
    }

    public DeliveryStatus getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLeaseOwner() {
        return leaseOwner;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public TerminalReason getTerminalReason() {
        return terminalReason;
    }

    public AttemptOutcome getLastOutcome() {
        return lastOutcome;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getFirstAttemptAt() {
        return firstAttemptAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public long getVersion() {
        return version;
    }

    /** Lazy and unmodifiable. Prefer {@code DeliveryAttemptRepository} for the history. */
    public List<DeliveryAttempt> getAttempts() {
        return Collections.unmodifiableList(attempts);
    }
}
