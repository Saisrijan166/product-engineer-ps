package com.caygnus.webhook.ingest.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The hash decides whether a resubmission is the same event or a caller bug, so it has to be
 * insensitive to how the JSON was written and sensitive to what it says.
 */
class PayloadHasherTest {

    @Test
    void theSamePayloadHashesTheSameWay() {
        Map<String, Object> payload = Map.of("incidentId", "inc_456", "severity", "high");

        assertThat(PayloadHasher.sha256(payload)).isEqualTo(PayloadHasher.sha256(payload));
    }

    @Test
    void keyOrderDoesNotChangeTheHash() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("incidentId", "inc_456");
        first.put("severity", "high");

        Map<String, Object> reversed = new LinkedHashMap<>();
        reversed.put("severity", "high");
        reversed.put("incidentId", "inc_456");

        // Two serialisers, one event. Rejecting the second as a mismatch would be wrong.
        assertThat(PayloadHasher.sha256(first)).isEqualTo(PayloadHasher.sha256(reversed));
    }

    @Test
    void keyOrderDoesNotChangeTheHashInsideNestedObjects() {
        Map<String, Object> first = Map.of("incident", orderedMap("id", "inc_456", "severity", "high"));
        Map<String, Object> reversed = Map.of("incident", orderedMap("severity", "high", "id", "inc_456"));

        // The sort has to reach all the way down; a one-level sort would pass the test above and
        // fail here.
        assertThat(PayloadHasher.sha256(first)).isEqualTo(PayloadHasher.sha256(reversed));
    }

    @Test
    void aChangedValueChangesTheHash() {
        assertThat(PayloadHasher.sha256(Map.of("severity", "high")))
                .isNotEqualTo(PayloadHasher.sha256(Map.of("severity", "low")));
    }

    @Test
    void anAddedFieldChangesTheHash() {
        assertThat(PayloadHasher.sha256(Map.of("a", 1)))
                .isNotEqualTo(PayloadHasher.sha256(Map.of("a", 1, "b", 2)));
    }

    @Test
    void arrayOrderChangesTheHashBecauseItIsSignificantInJson() {
        assertThat(PayloadHasher.sha256(Map.of("tags", List.of("a", "b"))))
                .isNotEqualTo(PayloadHasher.sha256(Map.of("tags", List.of("b", "a"))));
    }

    @Test
    void anEmptyPayloadHashesToSomethingStable() {
        assertThat(PayloadHasher.sha256(Map.of())).isEqualTo(PayloadHasher.sha256(Map.of()));
    }

    @Test
    void theHashFitsTheColumn() {
        assertThat(PayloadHasher.sha256(Map.of("severity", "high")))
                .hasSize(64)
                .matches("[0-9a-f]{64}");
    }

    private static Map<String, Object> orderedMap(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(k1, v1);
        map.put(k2, v2);
        return map;
    }
}
