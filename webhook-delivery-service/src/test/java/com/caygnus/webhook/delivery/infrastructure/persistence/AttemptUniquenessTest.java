package com.caygnus.webhook.delivery.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.caygnus.webhook.common.model.AttemptOutcome;
import com.caygnus.webhook.common.model.ErrorClass;
import com.caygnus.webhook.delivery.domain.Delivery;
import com.caygnus.webhook.delivery.domain.DeliveryAttempt;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceUnitUtil;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The attempt history: the evidence AC5 is judged on, and the constraint that makes a double
 * attempt impossible rather than merely unlikely.
 */
class AttemptUniquenessTest extends AbstractPersistenceTest {

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void thesameAttemptNumberCannotBeRecordedTwiceForOneDelivery() {
        UUID deliveryId = persistDelivery("evt_123", NOW).getId();
        inTransaction(() -> attempts.save(successfulAttempt(deliveryId, 1)));

        // Even if two workers somehow both ran attempt 1, only one record can exist. The count that
        // bounds retries is therefore never inflated by a duplicate.
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> inTransaction(() -> attempts.saveAndFlush(successfulAttempt(deliveryId, 1))));

        assertThat(attempts.countByDeliveryId(deliveryId)).isEqualTo(1);
    }

    @Test
    void differentAttemptNumbersCoexistAndComeBackInOrder() {
        UUID deliveryId = persistDelivery("evt_123", NOW).getId();

        // Inserted out of order on purpose: the ordering must come from the query, not from luck.
        inTransaction(() -> {
            attempts.save(successfulAttempt(deliveryId, 3));
            attempts.save(successfulAttempt(deliveryId, 1));
            attempts.save(successfulAttempt(deliveryId, 2));
        });

        assertThat(attempts.findByDeliveryIdOrderByAttemptNumberAsc(deliveryId))
                .extracting(DeliveryAttempt::getAttemptNumber)
                .containsExactly(1, 2, 3);
    }

    @Test
    void thesameAttemptNumberIsFineAcrossDifferentDeliveries() {
        UUID first = persistDelivery("evt_1", NOW).getId();
        UUID second = persistDelivery("evt_2", NOW).getId();

        inTransaction(() -> {
            attempts.save(successfulAttempt(first, 1));
            attempts.save(successfulAttempt(second, 1));
        });

        assertThat(attempts.count()).isEqualTo(2);
    }

    @Test
    void everyRecordedFieldSurvivesTheRoundTrip() {
        UUID deliveryId = persistDelivery("evt_123", NOW).getId();
        DeliveryAttempt recorded = DeliveryAttempt.record(
                deliveryId, 2, NOW, NOW.plusMillis(1_034),
                AttemptOutcome.RETRYABLE_FAILURE, 503, null, null,
                "service unavailable", 30, NOW.plusSeconds(30), WORKER);

        inTransaction(() -> attempts.save(recorded));

        assertThat(attempts.findByDeliveryIdOrderByAttemptNumberAsc(deliveryId)).singleElement().satisfies(a -> {
            assertThat(a.getAttemptNumber()).isEqualTo(2);
            assertThat(a.getStartedAt()).isEqualTo(NOW);
            assertThat(a.getCompletedAt()).isEqualTo(NOW.plusMillis(1_034));
            assertThat(a.getDurationMs()).isEqualTo(1_034);
            assertThat(a.getOutcome()).isEqualTo(AttemptOutcome.RETRYABLE_FAILURE);
            assertThat(a.getHttpStatus()).isEqualTo(503);
            assertThat(a.getErrorClass()).isNull();
            assertThat(a.getResponseBodySnippet()).isEqualTo("service unavailable");
            assertThat(a.getRetryAfterSeconds()).isEqualTo(30);
            assertThat(a.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(30));
            assertThat(a.getWorkerId()).isEqualTo(WORKER);
        });
    }

    @Test
    void aTransportFailureRecordsAnErrorClassAndNoHttpStatus() {
        UUID deliveryId = persistDelivery("evt_123", NOW).getId();
        DeliveryAttempt recorded = DeliveryAttempt.record(
                deliveryId, 1, NOW, NOW.plusSeconds(5),
                AttemptOutcome.RETRYABLE_FAILURE, null, ErrorClass.READ_TIMEOUT, "Read timed out",
                null, null, NOW.plusSeconds(5), WORKER);

        inTransaction(() -> attempts.save(recorded));

        assertThat(attempts.findByDeliveryIdOrderByAttemptNumberAsc(deliveryId)).singleElement().satisfies(a -> {
            assertThat(a.getHttpStatus()).isNull();
            assertThat(a.getErrorClass()).isEqualTo(ErrorClass.READ_TIMEOUT);
            assertThat(a.getErrorMessage()).isEqualTo("Read timed out");
        });
    }

    @Test
    void anEnormousResponseBodyIsTruncatedBeforeItReachesTheDatabase() {
        UUID deliveryId = persistDelivery("evt_123", NOW).getId();
        String hugeErrorPage = "x".repeat(50_000);

        inTransaction(() -> attempts.save(DeliveryAttempt.record(
                deliveryId, 1, NOW, NOW.plusMillis(10),
                AttemptOutcome.PERMANENT_FAILURE, 400, null, hugeErrorPage,
                hugeErrorPage, null, null, WORKER)));

        // A receiver is free to answer a failed webhook with a megabyte of HTML. Storing that per
        // attempt, per delivery, forever is how an audit trail turns into an outage.
        assertThat(attempts.findByDeliveryIdOrderByAttemptNumberAsc(deliveryId)).singleElement().satisfies(a -> {
            assertThat(a.getResponseBodySnippet()).hasSize(1_024);
            assertThat(a.getErrorMessage()).hasSize(512);
        });
    }

    @Test
    void deletingADeliveryTakesItsHistoryWithIt() {
        UUID deliveryId = persistDelivery("evt_123", NOW).getId();
        inTransaction(() -> IntStream.rangeClosed(1, 3).forEach(n -> attempts.save(successfulAttempt(deliveryId, n))));

        inTransaction(() -> deliveries.deleteById(deliveryId));

        assertThat(attempts.countByDeliveryId(deliveryId)).isZero();
    }

    @Test
    void anAttemptCannotBelongToADeliveryThatDoesNotExist() {
        DeliveryAttempt orphan = successfulAttempt(UUID.randomUUID(), 1);

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> inTransaction(() -> attempts.saveAndFlush(orphan)));
    }

    @Test
    void attemptsAreNotLoadedUntilSomethingAsksForThem() {
        UUID deliveryId = persistDelivery("evt_123", NOW).getId();
        inTransaction(() -> attempts.save(successfulAttempt(deliveryId, 1)));

        // Asked of the attribute, not of whatever the getter hands back. Delivery.getAttempts()
        // wraps the collection in an unmodifiableList, and Hibernate.isInitialized() on that
        // wrapper reports true for any non-proxy -- it would pass here whatever the fetch type
        // said, which would make this test a decoration rather than a check.
        PersistenceUnitUtil util = entityManagerFactory.getPersistenceUnitUtil();

        // The mapping stays LAZY: an eager collection here would load every attempt of every
        // delivery the read API lists, which is exactly the N+1 the design set out to avoid.
        Delivery loaded = inTransaction(() -> deliveries.findById(deliveryId).orElseThrow());
        assertThat(util.isLoaded(loaded, "attempts")).isFalse();
    }

    @Test
    void theAttemptsCollectionStillLoadsWhenItIsAskedFor() {
        UUID deliveryId = persistDelivery("evt_123", NOW).getId();
        inTransaction(() -> IntStream.rangeClosed(1, 3).forEach(n -> attempts.save(successfulAttempt(deliveryId, n))));

        // Lazy means deferred, not absent. Read inside a transaction, because that is the only
        // place a lazy collection can still reach the session it came from.
        List<Integer> numbers = inTransaction(() -> deliveries.findById(deliveryId).orElseThrow()
                .getAttempts().stream()
                .map(DeliveryAttempt::getAttemptNumber)
                .toList());

        assertThat(numbers).containsExactly(1, 2, 3);
    }

    private DeliveryAttempt successfulAttempt(UUID deliveryId, int attemptNumber) {
        return DeliveryAttempt.record(
                deliveryId, attemptNumber, NOW, NOW.plusMillis(120),
                AttemptOutcome.SUCCESS, 200, null, null, "ok", null, null, WORKER);
    }
}
