package com.abi.compare.storage;

import com.abi.compare.diff.Change;
import com.abi.compare.diff.DecisionView;
import com.abi.compare.diff.DiffEngine;
import com.abi.compare.diff.DiffResult;
import com.abi.compare.diff.EntryChange;
import com.abi.compare.model.Decision;
import com.abi.compare.model.Snapshot;
import com.abi.compare.rule.RuleRegistry;
import com.abi.compare.rule.RuleSet;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ComparisonService {

    private final Repository repo;
    private final SnapshotService snapshots;
    private final RuleRegistry rules;
    private final DiffEngine engine;
    private final ObjectMapper mapper;

    public ComparisonService(Repository repo, SnapshotService snapshots, RuleRegistry rules,
                             DiffEngine engine, ObjectMapper mapper) {
        this.repo = repo;
        this.snapshots = snapshots;
        this.rules = rules;
        this.engine = engine;
        this.mapper = mapper;
    }

    /**
     * Compares two releases under a rule set. The whole operation is atomic;
     * identical requests return the existing comparison (idempotent publish).
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public DiffResult compare(String oldHash, String newHash, String ruleSetId) {
        Optional<Map<String, Object>> existing = repo.findComparison(oldHash, newHash, ruleSetId);
        if (existing.isPresent()) {
            return hydrate(existing.get());
        }
        Snapshot oldSnap = snapshots.load(oldHash);
        Snapshot newSnap = snapshots.load(newHash);
        RuleSet ruleSet = rules.find(ruleSetId)
                .orElseThrow(() -> new IllegalArgumentException("unknown rule set " + ruleSetId));
        DiffResult result = engine.compare(oldSnap, newSnap, ruleSet);
        String id = oldHash.substring(0, 12) + "_" + newHash.substring(0, 12) + "_" + ruleSetId;
        result.id = id;
        result.version = 0;
        String payload;
        try {
            payload = mapper.writeValueAsString(result);
        } catch (Exception e) {
            throw new IllegalStateException("cannot serialize diff", e);
        }
        repo.insertComparison(id, oldHash, newHash, result.component, result.oldRelease,
                result.newRelease, result.platform, result.arch, ruleSetId, ruleSet.version,
                payload, result.createdAt);
        return result;
    }

    public DiffResult get(String id) {
        return hydrate(repo.findComparisonById(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown comparison " + id)));
    }

    public List<Map<String, Object>> list() {
        return repo.listComparisons();
    }

    /**
     * Records a verdict in one atomic transaction. The client must send the
     * comparison version it reviewed; any newer decision on the same symbol
     * (including a global/platform overlap) is a symbol-level conflict.
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public DecisionView decide(String comparisonId, Decision input) {
        Map<String, Object> row = repo.findComparisonById(comparisonId)
                .orElseThrow(() -> new IllegalArgumentException("unknown comparison " + comparisonId));
        long currentVersion = ((Number) row.get("version")).longValue();
        long baseVersion = input.baseVersion == null ? currentVersion : input.baseVersion;
        if (baseVersion > currentVersion) {
            throw new DecisionConflictException(currentVersion, List.of());
        }
        validateDecision(input);

        List<Map<String, Object>> conflicts = repo.findConflicts(comparisonId, input.stableId,
                input.changeKind, input.platform, baseVersion);
        if (!conflicts.isEmpty()) {
            throw new DecisionConflictException(currentVersion, conflicts);
        }
        String decidedAt = Instant.now().toString();
        repo.insertDecision(comparisonId, input.stableId, input.changeKind,
                input.platform, input.verdict, input.rationale, input.effectiveFrom,
                input.expiresAfterVersion, currentVersion, decidedAt);
        int updated = repo.bumpComparisonVersion(comparisonId, currentVersion);
        if (updated == 0) {
            throw new DecisionConflictException(repo.comparisonVersion(comparisonId), List.of());
        }
        String newRelease = (String) row.get("new_release");
        return toView(input, decidedAt, currentVersion, newRelease);
    }

    private void validateDecision(Decision d) {
        if (d.stableId == null || d.stableId.isBlank()) {
            throw new IllegalArgumentException("decision requires stableId");
        }
        if (d.platform == null || d.platform.isBlank()) {
            d.platform = "*";
        }
        if (!"ACCEPT".equals(d.verdict) && !"REJECT".equals(d.verdict)) {
            throw new IllegalArgumentException("verdict must be ACCEPT or REJECT");
        }
        if (d.effectiveFrom == null || d.effectiveFrom.isBlank()) {
            throw new IllegalArgumentException("exception needs an effectiveFrom version");
        }
    }

    private DiffResult hydrate(Map<String, Object> row) {
        try {
            DiffResult result = mapper.readValue((String) row.get("diff_payload"),
                    DiffResult.class);
            result.id = (String) row.get("id");
            result.version = ((Number) row.get("version")).longValue();
            attachDecisions(result, row);
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("corrupt comparison payload", e);
        }
    }

    private void attachDecisions(DiffResult result, Map<String, Object> row) {
        String newRelease = (String) row.get("new_release");
        Map<String, List<DecisionView>> byStableId = new LinkedHashMap<>();
        for (Map<String, Object> dRow : repo.listDecisions(result.id)) {
            Decision d = new Decision();
            d.stableId = (String) dRow.get("stable_id");
            d.changeKind = (String) dRow.get("change_kind");
            d.platform = (String) dRow.get("platform");
            d.verdict = (String) dRow.get("verdict");
            d.rationale = (String) dRow.get("rationale");
            d.effectiveFrom = (String) dRow.get("effective_from");
            d.expiresAfterVersion = (String) dRow.get("expires_after_version");
            d.baseVersion = ((Number) dRow.get("base_version")).longValue();
            d.decidedAt = (String) dRow.get("decided_at");
            DecisionView view = toView(d, d.decidedAt, d.baseVersion, newRelease);
            byStableId.computeIfAbsent(d.stableId, k -> new ArrayList<>()).add(view);
        }
        for (List<EntryChange> group : List.of(result.symbols, result.types,
                result.affectedPublicEntries)) {
            if (group == null) {
                continue;
            }
            for (EntryChange entry : group) {
                List<DecisionView> views = byStableId.get(entry.stableId);
                if (views != null) {
                    entry.decisions = views;
                    markChanges(entry, views);
                }
            }
        }
    }

    private void markChanges(EntryChange entry, List<DecisionView> views) {
        for (Change change : entry.changes) {
            for (DecisionView v : views) {
                if (v.changeKindRefMatches(change) && "ACCEPT".equals(v.verdict)
                        && "ACTIVE".equals(v.status)) {
                    change.exceptionRef = v.platform + "@" + v.effectiveFrom;
                }
            }
        }
    }

    private DecisionView toView(Decision d, String decidedAt, long baseVersion, String newRelease) {
        DecisionView view = new DecisionView();
        view.changeKind = d.changeKind;
        view.platform = d.platform;
        view.verdict = d.verdict;
        view.rationale = d.rationale;
        view.effectiveFrom = d.effectiveFrom;
        view.expiresAfterVersion = d.expiresAfterVersion;
        view.baseVersion = baseVersion;
        view.decidedAt = decidedAt;
        view.status = status(d, newRelease);
        return view;
    }

    /**
     * ACTIVE when the compared release is inside [effectiveFrom,
     * expiresAfterVersion]; EXPIRED past the expiry. A rule-set change never
     * extends an existing exception (expiry is evaluated against release only).
     */
    private String status(Decision d, String release) {
        if (release != null && VersionOrder.compare(release, d.effectiveFrom) < 0) {
            return "PENDING";
        }
        if (d.expiresAfterVersion != null
                && VersionOrder.compare(release, d.expiresAfterVersion) > 0) {
            return "EXPIRED";
        }
        return "ACTIVE";
    }
}
