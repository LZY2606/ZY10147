package com.abi.compare.storage;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class Repository {

    private final JdbcTemplate jdbc;

    public Repository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Map<String, Object>> findSnapshot(String hash) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM snapshots WHERE content_hash = ?", hash);
        return rows.stream().findFirst();
    }

    public void insertSnapshotIgnore(String hash, String component, String release,
                                     String platform, String arch,
                                     String extractorName, String extractorVersion,
                                     String payload, String importedAt) {
        jdbc.update("INSERT OR IGNORE INTO snapshots(content_hash, component, release, platform,"
                        + " arch, extractor_name, extractor_version, payload, imported_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?)",
                hash, component, release, platform, arch,
                extractorName, extractorVersion, payload, importedAt);
    }

    public List<Map<String, Object>> listSnapshots() {
        return jdbc.queryForList(
                "SELECT content_hash, component, release, platform, arch, extractor_name,"
                        + " extractor_version, imported_at FROM snapshots"
                        + " ORDER BY component, release, platform, arch");
    }

    public Optional<Map<String, Object>> findComparison(
            String oldHash, String newHash, String ruleSetId) {
        return jdbc.queryForList("SELECT * FROM comparisons WHERE old_snapshot_hash = ?"
                + " AND new_snapshot_hash = ? AND rule_set_id = ?", oldHash, newHash, ruleSetId)
                .stream().findFirst();
    }

    public Optional<Map<String, Object>> findComparisonById(String id) {
        return jdbc.queryForList("SELECT * FROM comparisons WHERE id = ?", id).stream()
                .findFirst();
    }

    public void insertComparison(String id, String oldHash, String newHash,
                                 String component, String oldRelease, String newRelease,
                                 String platform, String arch, String ruleSetId,
                                 String ruleSetVersion, String diffPayload, String createdAt) {
        jdbc.update("INSERT INTO comparisons(id, old_snapshot_hash, new_snapshot_hash, component,"
                        + " old_release, new_release, platform, arch, rule_set_id,"
                        + " rule_set_version, diff_payload, version, created_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,0,?)",
                id, oldHash, newHash, component, oldRelease, newRelease, platform, arch,
                ruleSetId, ruleSetVersion, diffPayload, createdAt);
    }

    public List<Map<String, Object>> listComparisons() {
        return jdbc.queryForList("SELECT id, old_snapshot_hash, new_snapshot_hash, component,"
                + " old_release, new_release, platform, arch, rule_set_id, rule_set_version,"
                + " version, created_at FROM comparisons ORDER BY created_at DESC");
    }

    public long comparisonVersion(String comparisonId) {
        Long v = jdbc.queryForObject(
                "SELECT version FROM comparisons WHERE id = ?", Long.class, comparisonId);
        return v == null ? 0 : v;
    }

    /** Symbol-level conflicts: newer decisions for the same symbol on the
     * same or overlapping platform scope. */
    public List<Map<String, Object>> findConflicts(String comparisonId, String stableId,
                                                   String changeKind, String platform,
                                                   long clientBaseVersion) {
        return jdbc.queryForList(
                "SELECT stable_id, change_kind, platform, verdict, base_version, decided_at,"
                        + " rationale FROM decisions WHERE comparison_id = ? AND stable_id = ?"
                        + " AND (change_kind IS ? OR change_kind = ?)"
                        + " AND (platform = '*' OR ? = '*' OR platform = ?)"
                        + " AND base_version >= ?",
                comparisonId, stableId, changeKind, changeKind, platform, platform,
                clientBaseVersion);
    }

    public void insertDecision(String comparisonId, String stableId, String changeKind,
                               String platform, String verdict, String rationale,
                               String effectiveFrom, String expiresAfterVersion,
                               long baseVersion, String decidedAt) {
        jdbc.update("INSERT INTO decisions(comparison_id, stable_id, change_kind, platform,"
                        + " verdict, rationale, effective_from, expires_after_version,"
                        + " base_version, decided_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                comparisonId, stableId, changeKind, platform, verdict, rationale,
                effectiveFrom, expiresAfterVersion, baseVersion, decidedAt);
    }

    public int bumpComparisonVersion(String comparisonId, long expectedVersion) {
        return jdbc.update("UPDATE comparisons SET version = version + 1 WHERE id = ?"
                + " AND version = ?", comparisonId, expectedVersion);
    }

    public List<Map<String, Object>> listDecisions(String comparisonId) {
        return jdbc.queryForList("SELECT * FROM decisions WHERE comparison_id = ?"
                + " ORDER BY stable_id, platform", comparisonId);
    }
}
