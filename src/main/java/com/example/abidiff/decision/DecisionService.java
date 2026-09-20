package com.example.abidiff.decision;

import com.example.abidiff.json.Json;
import com.example.abidiff.model.Enums.DecisionKind;
import com.example.abidiff.model.SemVer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Reviewer decisions live in a separate SQLite database from snapshots.
 *
 * <p>Submissions use optimistic concurrency on a per-comparison revision: the
 * caller presents the base revision it last saw; if another decision landed in
 * between, the write fails with the conflicting symbols so the UI can show a
 * symbol-level merge instead of silently overwriting.
 */
@Service
public class DecisionService {

    private final JdbcTemplate jdbc;

    public DecisionService(JdbcTemplate decisionJdbc) {
        this.jdbc = decisionJdbc;
    }

    public record SubmitRequest(String comparisonId, String symbolStableId, String platform,
                                DecisionKind decision, String reason, String actor,
                                String scopeFromVersion, String scopeToVersion,
                                String expiresAtVersion, String rulesetId,
                                Integer baseComparisonRev) {
    }

    public record ConflictEntry(String symbolStableId, String platform, String decision,
                                String reason, int currentRev) {
    }

    public record SubmitResult(String status, int newRevision, List<ResolutionView> applied,
                               List<ConflictEntry> conflicts) {
    }

    public static final class ConflictException extends RuntimeException {
        public final List<ConflictEntry> conflicts;
        public final int currentRev;

        public ConflictException(int currentRev, List<ConflictEntry> conflicts) {
            super("comparison revision changed; symbol-level conflicts present");
            this.currentRev = currentRev;
            this.conflicts = conflicts;
        }
    }

    @Transactional("decisionTransactionManager")
    public SubmitResult submit(SubmitRequest req) {
        validate(req);
        int current = rev(req.comparisonId());
        int base = req.baseComparisonRev() == null ? current : req.baseComparisonRev();
        if (base != current) {
            List<ConflictEntry> conflicts = conflictsSince(req.comparisonId(), base,
                    List.of(req.symbolStableId()));
            recordEvent(null, req, "CONFLICT", req.actor());
            throw new ConflictException(current, conflicts);
        }

        String id = "dec-" + UUID.randomUUID();
        String platform = req.platform() == null || req.platform().isBlank() ? "*" : req.platform();
        jdbc.update("INSERT INTO resolution(id, comparison_id, symbol_stable_id, platform, decision, reason, "
                        + "scope_from_version, scope_to_version, expires_at_version, ruleset_id, "
                        + "base_comparison_rev, created_by, created_at) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?) "
                        + "ON CONFLICT(comparison_id, symbol_stable_id, platform) DO UPDATE SET "
                        + "decision=excluded.decision, reason=excluded.reason, "
                        + "scope_from_version=excluded.scope_from_version, "
                        + "scope_to_version=excluded.scope_to_version, "
                        + "expires_at_version=excluded.expires_at_version, "
                        + "ruleset_id=excluded.ruleset_id, base_comparison_rev=excluded.base_comparison_rev, "
                        + "created_by=excluded.created_by, created_at=excluded.created_at, expired_at=NULL",
                id, req.comparisonId(), req.symbolStableId(), platform, req.decision().name(),
                req.reason(), req.scopeFromVersion(), req.scopeToVersion(), req.expiresAtVersion(),
                req.rulesetId(), base, req.actor(), Instant.now().toString());

        int newRev = current + 1;
        jdbc.update("INSERT INTO comparison_rev(comparison_id, rev) VALUES(?,?) "
                        + "ON CONFLICT(comparison_id) DO UPDATE SET rev=excluded.rev",
                req.comparisonId(), newRev);
        recordEvent(id, req, "APPLIED", req.actor());
        return new SubmitResult("APPLIED", newRev,
                forComparison(req.comparisonId()), List.of());
    }

    public int rev(String comparisonId) {
        List<Integer> revs = jdbc.queryForList(
                "SELECT rev FROM comparison_rev WHERE comparison_id=?", Integer.class, comparisonId);
        return revs.isEmpty() ? 0 : revs.get(0);
    }

    private void validate(SubmitRequest req) {
        if (req.comparisonId() == null || req.symbolStableId() == null
                || req.decision() == null || req.rulesetId() == null) {
            throw new IllegalArgumentException(
                    "comparisonId, symbolStableId, decision and rulesetId are required");
        }
        if (req.decision() == DecisionKind.EXCEPTION) {
            if (req.scopeFromVersion() == null || req.scopeToVersion() == null
                    || req.expiresAtVersion() == null) {
                throw new IllegalArgumentException(
                        "exception requires scopeFromVersion, scopeToVersion and expiresAtVersion");
            }
            SemVer from = SemVer.parse(req.scopeFromVersion());
            SemVer to = SemVer.parse(req.scopeToVersion());
            SemVer expires = SemVer.parse(req.expiresAtVersion());
            if (to.compareTo(from) < 0) {
                throw new IllegalArgumentException("scopeToVersion must be >= scopeFromVersion");
            }
            if (expires.compareTo(to) < 0) {
                throw new IllegalArgumentException("expiresAtVersion must cover scopeToVersion");
            }
        }
    }

    private List<ConflictEntry> conflictsSince(String comparisonId, int base, List<String> symbols) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT symbol_stable_id, platform, decision, reason FROM resolution "
                        + "WHERE comparison_id=? AND symbol_stable_id IN (" + placeholders(symbols.size()) + ")",
                mergeArgs(comparisonId, symbols));
        List<ConflictEntry> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            out.add(new ConflictEntry((String) row.get("symbol_stable_id"),
                    (String) row.get("platform"), (String) row.get("decision"),
                    (String) row.get("reason"), rev(comparisonId)));
        }
        return out;
    }

    private Object[] mergeArgs(String first, List<String> rest) {
        List<Object> args = new ArrayList<>();
        args.add(first);
        args.addAll(rest);
        return args.toArray();
    }

    private String placeholders(int n) {
        return String.join(",", Collections.nCopies(n, "?"));
    }

    private void recordEvent(String resolutionId, SubmitRequest req, String result, String actor) {
        jdbc.update("INSERT INTO resolution_event(resolution_id, comparison_id, symbol_stable_id, "
                        + "platform, payload_json, result, actor, at) VALUES(?,?,?,?,?,?,?,?)",
                resolutionId, req.comparisonId(), req.symbolStableId(),
                req.platform(), Json.write(req), result, actor, Instant.now().toString());
    }

    public List<ResolutionView> forComparison(String comparisonId) {
        return jdbc.query(
                "SELECT id, comparison_id, symbol_stable_id, platform, decision, reason, "
                        + "scope_from_version, scope_to_version, expires_at_version, ruleset_id, "
                        + "base_comparison_rev, created_by, created_at, expired_at "
                        + "FROM resolution WHERE comparison_id=? ORDER BY created_at",
                (rs, n) -> new ResolutionView(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                        rs.getString(8), rs.getString(9), rs.getString(10), rs.getInt(11),
                        rs.getString(12), rs.getString(13), rs.getString(14)),
                comparisonId);
    }

    /**
     * Decisions applicable to a symbol in a (comparison, platform, evaluated
     * version, ruleset) context. A rule revision bump does NOT extend old
     * exceptions: the bound ruleset_id must match the comparison's ruleset.
     */
    public List<ResolutionView> applicable(String comparisonId, String platform,
                                           String evaluatedVersion, String rulesetId) {
        SemVer v = SemVer.parse(evaluatedVersion);
        return forComparison(comparisonId).stream()
                .filter(r -> r.expiredAt() == null)
                .filter(r -> r.rulesetId().equals(rulesetId))
                .filter(r -> "*".equals(r.platform()) || r.platform().equals(platform))
                .filter(r -> "EXCEPTION".equals(r.decision()) ? inScope(r, v) : true)
                .toList();
    }

    private boolean inScope(ResolutionView r, SemVer v) {
        if (r.scopeFromVersion() == null || r.scopeToVersion() == null) {
            return false;
        }
        SemVer from = SemVer.parse(r.scopeFromVersion());
        SemVer to = SemVer.parse(r.scopeToVersion());
        SemVer expires = SemVer.parse(r.expiresAtVersion());
        return v.within(from, to) && v.compareTo(expires) <= 0;
    }

    @Transactional("decisionTransactionManager")
    public void expireEarly(String resolutionId, String actor) {
        int updated = jdbc.update(
                "UPDATE resolution SET expired_at=? WHERE id=? AND expired_at IS NULL",
                Instant.now().toString(), resolutionId);
        if (updated > 0) {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT comparison_id, symbol_stable_id, platform FROM resolution WHERE id=?",
                    resolutionId);
            jdbc.update("INSERT INTO resolution_event(resolution_id, comparison_id, symbol_stable_id, "
                            + "platform, payload_json, result, actor, at) VALUES(?,?,?,?,?,?,?,?)",
                    resolutionId, row.get("comparison_id"), row.get("symbol_stable_id"),
                    row.get("platform"), "{}", "EXPIRED_EARLY", actor, Instant.now().toString());
        }
    }

    public List<Map<String, Object>> events(String comparisonId) {
        return jdbc.queryForList(
                "SELECT id, resolution_id, symbol_stable_id, platform, result, actor, at, payload_json "
                        + "FROM resolution_event WHERE comparison_id=? ORDER BY id",
                comparisonId);
    }

    public record ResolutionView(String id, String comparisonId, String symbolStableId,
                                 String platform, String decision, String reason,
                                 String scopeFromVersion, String scopeToVersion,
                                 String expiresAtVersion, String rulesetId, int baseComparisonRev,
                                 String createdBy, String createdAt, String expiredAt) {
    }
}
