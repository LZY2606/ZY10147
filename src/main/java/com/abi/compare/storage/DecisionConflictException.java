package com.abi.compare.storage;

import java.util.List;
import java.util.Map;

/** Raised when a decision is submitted against a stale or conflicting base. */
public class DecisionConflictException extends RuntimeException {

    private final long currentVersion;
    private final List<Map<String, Object>> conflicts;

    public DecisionConflictException(long currentVersion, List<Map<String, Object>> conflicts) {
        super("comparison version moved to " + currentVersion
                + "; symbol-level conflicts: " + conflicts.size());
        this.currentVersion = currentVersion;
        this.conflicts = conflicts;
    }

    public long getCurrentVersion() {
        return currentVersion;
    }

    public List<Map<String, Object>> getConflicts() {
        return conflicts;
    }
}
