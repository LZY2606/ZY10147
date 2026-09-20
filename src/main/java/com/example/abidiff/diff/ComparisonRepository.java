package com.example.abidiff.diff;

import com.example.abidiff.json.Json;
import com.example.abidiff.model.Enums.Boundary;
import com.example.abidiff.model.Enums.Category;
import com.example.abidiff.model.Enums.Severity;
import com.example.abidiff.snapshot.SnapshotRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.example.abidiff.model.StoredTypes.LoadedSnapshot;
import com.example.abidiff.rules.RuleRepository;
import com.example.abidiff.rules.RuleSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Runs and persists atomic comparisons. A comparison is keyed by an input hash
 * covering both snapshot content hashes and the ruleset id, so:
 * <ul>
 *   <li>identical submissions are idempotent (same row returned);</li>
 *   <li>the same snapshots evaluated under different ruleset revisions produce
 *       separate, coexisting, reproducible comparisons.</li>
 * </ul>
 */
@Repository
public class ComparisonRepository {

    private static final TypeReference<Map<String, Object>> DETAIL_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRINGS_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final SnapshotRepository snapshots;
    private final RuleRepository rules;

    public ComparisonRepository(JdbcTemplate snapshotJdbc, SnapshotRepository snapshots,
                                RuleRepository rules) {
        this.jdbc = snapshotJdbc;
        this.snapshots = snapshots;
        this.rules = rules;
    }

    @Transactional("snapshotTransactionManager")
    public ComparisonResult compare(String leftSnapshotId, String rightSnapshotId, String rulesetId) {
        LoadedSnapshot left = snapshots.load(leftSnapshotId);
        LoadedSnapshot right = snapshots.load(rightSnapshotId);
        if (!left.snapshot().platform().equals(right.snapshot().platform())
                || !left.snapshot().arch().equals(right.snapshot().arch())) {
            throw new IllegalArgumentException(
                    "snapshots must share platform/arch for an ABI comparison");
        }
        RuleSet rs = rulesetId == null || rulesetId.isBlank()
                ? rules.active(left.snapshot().platform(), left.snapshot().arch())
                : rules.byId(rulesetId);
        if (!rs.platform().equals(left.snapshot().platform())
                || !rs.arch().equals(left.snapshot().arch())) {
            throw new IllegalArgumentException("ruleset " + rs.id()
                    + " does not apply to " + left.snapshot().platform() + "/" + left.snapshot().arch());
        }

        String inputHash = SnapshotRepository.sha256(
                (left.snapshot().contentHash() + "|" + right.snapshot().contentHash()
                        + "|" + rs.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8));

        List<String> existingIds = jdbc.queryForList(
                "SELECT id FROM comparison WHERE input_hash=?", String.class, inputHash);
        if (!existingIds.isEmpty()) {
            String existing = existingIds.get(0);
            return get(existing);
        }

        DiffEngine.Result raw = DiffEngine.diff(left, right);
        List<Finding> evaluated = raw.findings().stream()
                .map(f -> withSeverity(f, rs))
                .toList();

        String id = "cmp-" + inputHash.substring(0, 20);
        jdbc.update("INSERT INTO comparison(id, left_snapshot_id, right_snapshot_id, platform, arch, "
                        + "ruleset_id, left_release, right_release, input_hash, created_at) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?)",
                id, leftSnapshotId, rightSnapshotId, left.snapshot().platform(),
                left.snapshot().arch(), rs.id(),
                left.snapshot().releaseId(), right.snapshot().releaseId(),
                inputHash, Instant.now().toString());

        int ordinal = 0;
        for (Finding f : evaluated) {
            jdbc.update("INSERT INTO finding(comparison_id, component_stable_id, symbol_stable_id, kind, "
                            + "category, severity, boundary, title, detail_json, affected_roots_json, ordinal) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    id, f.componentStableId(), f.symbolStableId(), f.kind(),
                    f.category().name(), f.severity().name(), f.boundary(), f.title(),
                    Json.write(f.detail()), Json.write(f.affectedRoots()), ordinal++);
        }
        return get(id);
    }

    private Finding withSeverity(Finding f, RuleSet rs) {
        Boundary boundary = Boundary.valueOf(f.boundary());
        Severity severity = rs.severity(boundary, f.kind(), f.severity());
        return new Finding(f.componentStableId(), f.symbolStableId(), f.kind(), f.category(),
                severity, f.boundary(), f.title(), f.detail(), f.affectedRoots());
    }

    public ComparisonResult get(String id) {
        return jdbc.queryForObject(
                "SELECT id, left_snapshot_id, right_snapshot_id, platform, arch, ruleset_id, "
                        + "left_release, right_release FROM comparison WHERE id=?",
                (rs, n) -> new ComparisonResult(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                        rs.getString(8), findings(rs.getString(1)), 0),
                id);
    }

    public List<Finding> findings(String comparisonId) {
        return jdbc.query(
                "SELECT component_stable_id, symbol_stable_id, kind, category, severity, boundary, "
                        + "title, detail_json, affected_roots_json FROM finding "
                        + "WHERE comparison_id=? ORDER BY ordinal",
                (rs, n) -> new Finding(rs.getString(1), rs.getString(2), rs.getString(3),
                        Category.valueOf(rs.getString(4)), Severity.valueOf(rs.getString(5)),
                        rs.getString(6), rs.getString(7),
                        Json.MAPPER.convertValue(Json.tree(rs.getString(8)), DETAIL_TYPE),
                        Json.MAPPER.convertValue(Json.tree(rs.getString(9)), STRINGS_TYPE)),
                comparisonId);
    }

    public List<Map<String, Object>> list() {
        return jdbc.queryForList(
                "SELECT c.id, c.left_snapshot_id, c.right_snapshot_id, c.platform, c.arch, "
                        + "c.ruleset_id, c.left_release, c.right_release, c.created_at, "
                        + "(SELECT COUNT(*) FROM finding f WHERE f.comparison_id=c.id) AS findings, "
                        + "(SELECT COUNT(*) FROM finding f WHERE f.comparison_id=c.id "
                        + "  AND f.severity='BREAKING') AS breaking "
                        + "FROM comparison c ORDER BY c.created_at DESC");
    }
}
