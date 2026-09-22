package com.caygnus.webhook.ingest.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/**
 * A fingerprint of an event's payload, used to tell a resubmission from a different event wearing
 * the same identifier.
 *
 * <p>Canonicalised before hashing, so the answer depends on what the payload <em>means</em> rather
 * than how it was typed. Map entries are sorted by key at every level, and whitespace is gone by
 * the time Jackson has parsed the request -- a caller that pretty-prints its JSON, or whose
 * serialiser happens to emit keys in a different order, is resubmitting the same event and should
 * be told so rather than accused of changing it.
 *
 * <p>Two things it deliberately does not do. Array order is significant, because it is significant
 * in JSON. And {@code 1} and {@code 1.0} hash differently, because they parse to different types
 * -- a caller that changes number formatting mid-stream is doing something worth noticing.
 */
public final class PayloadHasher {

    /**
     * Sorting is configured here rather than applied by hand: it reaches nested maps, which a
     * one-level sort would miss, and it keeps this class a single expression.
     */
    private static final ObjectMapper CANONICAL = JsonMapper.builder()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .build();

    private PayloadHasher() {
    }

    /** @return 64 lower-case hex characters, matching the {@code char(64)} column */
    public static String sha256(Map<String, Object> payload) {
        return hex(digest(canonicalise(payload)));
    }

    private static String canonicalise(Map<String, Object> payload) {
        try {
            return CANONICAL.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // Jackson parsed this map out of the request body moments ago, so it can write it.
            throw new IllegalStateException("Could not canonicalise an already-parsed payload", e);
        }
    }

    private static byte[] digest(String canonical) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // Every JVM is required to ship SHA-256.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
