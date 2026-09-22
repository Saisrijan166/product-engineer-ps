package com.caygnus.webhook.ingest.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.caygnus.webhook.ingest.support.AbstractIngestTest;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * <b>AC4 — idempotent ingestion, the concurrent half.</b>
 *
 * <p>Sequential idempotency is easy and proves little: check whether the row exists, and if not
 * insert it. That implementation passes every test in {@code IdempotentIngestTest} and falls over
 * the moment two requests arrive at once, because between the check and the insert another
 * transaction can do the same thing.
 *
 * <p>So this is the test that decides whether the design is real. Fifty simultaneous submissions
 * of one event, and no lock anywhere in the path -- not a row lock taken by the application, not
 * a table, not a distributed one. PostgreSQL serialises writes to the unique index and reports
 * the outcome as a row count, and that is the entire mechanism.
 */
class ConcurrentIngestTest extends AbstractIngestTest {

    private static final int CONCURRENT_SUBMISSIONS = 50;

    @Test
    void fiftySimultaneousSubmissionsProduceExactlyOneEventAndOneDispatch() throws Exception {
        List<Integer> statuses = submitSimultaneously("evt_race", CONCURRENT_SUBMISSIONS);

        // Exactly one caller is told it accepted the event.
        assertThat(statuses).filteredOn(status -> status == 201).hasSize(1);
        assertThat(statuses).filteredOn(status -> status == 200).hasSize(CONCURRENT_SUBMISSIONS - 1);
        assertThat(statuses).doesNotContain(409, 500, 503);

        assertThat(events.count()).isEqualTo(1);
        // One dispatch intent means one delivery job, which is the half of AC4 that actually
        // protects the receiver.
        assertThat(outbox.count()).isEqualTo(1);
    }

    @Test
    void everySubmissionAfterTheFirstIsCounted() throws Exception {
        submitSimultaneously("evt_race", CONCURRENT_SUBMISSIONS);

        // 49, not "somewhere near 49". The counter is incremented by PostgreSQL against a row it
        // holds a lock on; reading it into Java and adding one would lose most of these, and the
        // loss would be invisible because the count is only ever inspected, never acted on.
        assertThat(duplicateSubmissionCountOf("evt_race")).isEqualTo(CONCURRENT_SUBMISSIONS - 1);
    }

    @Test
    void concurrentSubmissionsOfDifferentEventsAllSucceed() throws Exception {
        List<Callable<Integer>> submissions = IntStream.range(0, CONCURRENT_SUBMISSIONS)
                .<Callable<Integer>>mapToObj(i -> () -> statusOf("evt_" + i))
                .toList();

        List<Integer> statuses = runConcurrently(submissions);

        // The idempotency must not have become a bottleneck that serialises unrelated traffic.
        assertThat(statuses).allMatch(status -> status == 201);
        assertThat(events.count()).isEqualTo(CONCURRENT_SUBMISSIONS);
        assertThat(outbox.count()).isEqualTo(CONCURRENT_SUBMISSIONS);
    }

    @Test
    void aConcurrentBurstLeavesTheEventExactlyAsASingleSubmissionWould() throws Exception {
        submitSimultaneously("evt_race", CONCURRENT_SUBMISSIONS);

        assertThat(jdbc.queryForMap(
                "SELECT event_id, type, duplicate_submission_count FROM webhook_event"))
                .containsEntry("event_id", "evt_race")
                .containsEntry("type", "incident.created")
                .containsEntry("duplicate_submission_count", CONCURRENT_SUBMISSIONS - 1);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_dispatch WHERE event_id = ?", Long.class, "evt_race"))
                .isEqualTo(1);
    }

    private List<Integer> submitSimultaneously(String eventId, int times) throws Exception {
        return runConcurrently(IntStream.range(0, times)
                .<Callable<Integer>>mapToObj(i -> () -> statusOf(eventId))
                .toList());
    }

    private List<Integer> runConcurrently(List<Callable<Integer>> submissions) throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            return pool.invokeAll(submissions).stream().map(ConcurrentIngestTest::awaitResult).toList();
        }
    }

    private int statusOf(String eventId) throws Exception {
        return mockMvc.perform(postEvent(eventId)).andReturn().getResponse().getStatus();
    }

    private static <T> T awaitResult(Future<T> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
