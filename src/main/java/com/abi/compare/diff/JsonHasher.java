package com.abi.compare.diff;

import com.abi.compare.model.Snapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Content-hash based snapshot de-duplication. */
public final class JsonHasher {
    private static final ObjectMapper CANON = new ObjectMapper()
            .findAndRegisterModules()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private JsonHasher() {
    }

    /** Canonical SHA-256 over the snapshot content, ignoring any stored hash. */
    public static String sha256(Snapshot snapshot) {
        String stored = snapshot.contentHash;
        snapshot.contentHash = null;
        try {
            byte[] canonical = CANON.writeValueAsBytes(snapshot);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("failed to hash snapshot", e);
        } finally {
            snapshot.contentHash = stored;
        }
    }
}
