package com.abi.compare;

import com.abi.compare.diff.JsonHasher;
import com.abi.compare.model.Extractor;
import com.abi.compare.model.Snapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContentHashTest {

    private Snapshot snapshot() {
        Snapshot s = new Snapshot();
        s.schemaVersion = 1;
        s.component = "c";
        s.release = "1.0";
        s.platform = "linux";
        s.arch = "aarch64";
        s.extractor = new Extractor("x", "1");
        return s;
    }

    @Test
    void equalContentHashesEqualRegardlessOfStoredHash() {
        Snapshot a = snapshot();
        Snapshot b = snapshot();
        b.contentHash = "preexisting-ignored-value";
        assertEquals(JsonHasher.sha256(a), JsonHasher.sha256(b));
    }

    @Test
    void contentChangeChangesHash() {
        Snapshot a = snapshot();
        Snapshot b = snapshot();
        b.release = "1.1";
        assertNotEquals(JsonHasher.sha256(a), JsonHasher.sha256(b));
    }
}
