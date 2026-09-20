package com.example.abidiff.web;

import com.example.abidiff.decision.DecisionService;
import com.example.abidiff.decision.DecisionService.ConflictException;
import com.example.abidiff.decision.DecisionService.ResolutionView;
import com.example.abidiff.diff.ComparisonRepository;
import com.example.abidiff.diff.ComparisonResult;
import com.example.abidiff.diff.Finding;
import com.example.abidiff.diff.ImpactGraph;
import com.example.abidiff.json.Json;
import com.example.abidiff.model.StoredTypes.LoadedSnapshot;
import com.example.abidiff.model.StoredTypes.RefRow;
import com.example.abidiff.model.StoredTypes.SymbolRow;
import com.example.abidiff.rules.RuleRepository;
import com.example.abidiff.rules.RuleSet;
import com.example.abidiff.snapshot.FixtureDecisionSeeder;
import com.example.abidiff.snapshot.SnapshotRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final SnapshotRepository snapshots;
    private final ComparisonRepository comparisons;
    private final RuleRepository rules;
    private final DecisionService decisions;
    private final FixtureDecisionSeeder fixtureDecisions;

    public ApiController(SnapshotRepository snapshots, ComparisonRepository comparisons,
                         RuleRepository rules, DecisionService decisions,
                         FixtureDecisionSeeder fixtureDecisions) {
        this.snapshots = snapshots;
        this.comparisons = comparisons;
        this.rules = rules;
        this.decisions = decisions;
        this.fixtureDecisions = fixtureDecisions;
    }

    // ---------- releases / snapshots ----------

    @GetMapping("/releases")
    public List<Map<String, Object>> releases() {
        List<Map<String, Object>> out = snapshots.listReleases();
        for (Map<String, Object> r : out) {
            r.put("snapshots", snapshots.listSnapshots((String) r.get("id")));
        }
        return out;
    }

    @GetMapping("/snapshots")
    public List<Map<String, Object>> snapshots() {
        return snapshots.listAllSnapshots();
    }

    @GetMapping("/snapshots/{id}")
    public Map<String, Object> snapshot(@PathVariable String id) {
        LoadedSnapshot s = snapshots.load(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("snapshot", snapshotRow(s));
        out.put("components", s.components());
        out.put("symbols", s.symbols().stream().map(this::symbolView).toList());
        out.put("aliases", s.aliases());
        out.put("refs", s.refs());
        return out;
    }

    public record ImportRequest(String dist, String version, String json) {
    }

    @PostMapping("/snapshots")
    public Map<String, Object> importSnapshot(@RequestBody ImportRequest req) {
        if (req.dist() == null || req.version() == null || req.json() == null) {
            throw new BadRequest("dist, version and json are required");
        }
        var row = snapshots.importSnapshot(req.dist(), req.version(), req.json());
        return snapshotRow(snapshots.load(row.id()));
    }

    // ---------- rules ----------

    @GetMapping("/rulesets")
    public List<RuleSet> rulesets() {
        return rules.all();
    }

    // ---------- comparisons ----------

    public record CompareRequest(String leftSnapshotId, String rightSnapshotId, String rulesetId,
                                 Boolean seedFixtureDecision) {
    }

    @PostMapping("/comparisons")
    public Map<String, Object> compare(@RequestBody CompareRequest req) {
        ComparisonResult c = comparisons.compare(
                req.leftSnapshotId(), req.rightSnapshotId(), req.rulesetId());
        if (Boolean.TRUE.equals(req.seedFixtureDecision())) {
            LoadedSnapshot right = snapshots.load(req.rightSnapshotId());
            fixtureDecisions.seedForLinuxComparison(c.id(), c.rulesetId(),
                    (String) snapshotRow(right).get("version"));
        }
        return comparisonView(c);
    }

    @GetMapping("/comparisons")
    public List<Map<String, Object>> comparisonList() {
        return comparisons.list();
    }

    @GetMapping("/comparisons/{id}")
    public Map<String, Object> comparison(@PathVariable String id) {
        return comparisonView(comparisons.get(id));
    }

    @GetMapping("/comparisons/{id}/graph")
    public Map<String, Object> graph(@PathVariable String id) {
        ComparisonResult c = comparisons.get(id);
        LoadedSnapshot right = snapshots.load(c.rightSnapshotId());
        LoadedSnapshot left = snapshots.load(c.leftSnapshotId());
        ImpactGraph g = new ImpactGraph(right);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("comparisonId", id);
        out.put("nodes", nodes(right));
        out.put("edges", g.refs().stream().map(this::edgeView).toList());
        out.put("leftNodes", nodes(left));
        out.put("findings", c.findings().stream()
                .map(f -> Map.of("symbol", f.symbolStableId() == null ? "" : f.symbolStableId(),
                        "kind", f.kind(), "severity", f.severity().name(),
                        "affectedRoots", f.affectedRoots()))
                .toList());
        return out;
    }

    private List<Map<String, Object>> nodes(LoadedSnapshot s) {
        return s.symbols().stream()
                .map(sy -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", sy.stableId());
                    m.put("name", sy.name());
                    m.put("kind", sy.kind());
                    m.put("boundary", sy.boundary());
                    m.put("visibility", sy.visibility());
                    m.put("component", sy.componentStableId());
                    return m;
                })
                .toList();
    }

    private Map<String, Object> edgeView(RefRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", r.from());
        m.put("to", r.to());
        m.put("kind", r.kind().name());
        return m;
    }

    // ---------- decisions ----------

    @PostMapping("/decisions")
    public DecisionService.SubmitResult submit(@RequestBody DecisionService.SubmitRequest req) {
        return decisions.submit(req);
    }

    @GetMapping("/comparisons/{id}/decisions")
    public List<ResolutionView> decisions(@PathVariable String id) {
        return decisions.forComparison(id);
    }

    @GetMapping("/comparisons/{id}/events")
    public List<Map<String, Object>> events(@PathVariable String id) {
        return decisions.events(id);
    }

    public record ExpireRequest(String actor) {
    }

    @PostMapping("/decisions/{resolutionId}/expire")
    public Map<String, Object> expire(@PathVariable String resolutionId, @RequestBody ExpireRequest req) {
        decisions.expireEarly(resolutionId, req.actor() == null ? "reviewer" : req.actor());
        return Map.of("status", "EXPIRED_EARLY");
    }

    // ---------- view assembly ----------

    private Map<String, Object> comparisonView(ComparisonResult c) {
        LoadedSnapshot right = snapshots.load(c.rightSnapshotId());
        String rightVersion = (String) snapshotRow(right).get("version");
        int rev = decisions.rev(c.id());

        List<Map<String, Object>> findings = new ArrayList<>();
        for (Finding f : c.findings()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("componentStableId", f.componentStableId());
            m.put("symbolStableId", f.symbolStableId());
            m.put("kind", f.kind());
            m.put("category", f.category().name());
            m.put("severity", f.severity().name());
            m.put("boundary", f.boundary());
            m.put("title", f.title());
            m.put("detail", f.detail());
            m.put("affectedRoots", f.affectedRoots());

            List<ResolutionView> applies = f.symbolStableId() == null ? List.of()
                    : decisions.applicable(c.id(), c.platform(), rightVersion, c.rulesetId())
                    .stream()
                    .filter(r -> r.symbolStableId().equals(f.symbolStableId()))
                    .toList();
            ResolutionView exception = applies.stream()
                    .filter(r -> "EXCEPTION".equals(r.decision()))
                    .findFirst().orElse(null);
            m.put("exception", exception);
            m.put("effectiveSeverity", exception != null ? "ALLOWED" : f.severity().name());
            m.put("decisions", applies);
            findings.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", c.id());
        out.put("leftSnapshotId", c.leftSnapshotId());
        out.put("rightSnapshotId", c.rightSnapshotId());
        out.put("platform", c.platform());
        out.put("arch", c.arch());
        out.put("rulesetId", c.rulesetId());
        out.put("leftRelease", c.leftRelease());
        out.put("rightRelease", c.rightRelease());
        out.put("rightVersion", rightVersion);
        out.put("revision", rev);
        out.put("findings", findings);
        out.put("summary", summarize(findings));
        return out;
    }

    private Map<String, Object> summarize(List<Map<String, Object>> findings) {
        Map<String, Long> bySeverity = new TreeMap<>();
        Map<String, Long> byCategory = new TreeMap<>();
        for (Map<String, Object> f : findings) {
            bySeverity.merge((String) f.get("effectiveSeverity"), 1L, Long::sum);
            byCategory.merge((String) f.get("category"), 1L, Long::sum);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", findings.size());
        m.put("bySeverity", bySeverity);
        m.put("byCategory", byCategory);
        return m;
    }

    private Map<String, Object> snapshotRow(LoadedSnapshot s) {
        Map<String, Object> m = new LinkedHashMap<>();
        var snap = s.snapshot();
        m.put("id", snap.id());
        m.put("releaseId", snap.releaseId());
        m.put("platform", snap.platform());
        m.put("arch", snap.arch());
        m.put("extractor", snap.extractor());
        m.put("extractorVersion", snap.extractorVersion());
        m.put("contentHash", snap.contentHash());
        m.put("createdAt", snap.createdAt());
        Map<String, Object> rel = releaseInfo(snap.releaseId());
        if (rel != null) {
            m.put("dist", rel.get("dist"));
            m.put("version", rel.get("version"));
        }
        return m;
    }

    private Map<String, Object> releaseInfo(String releaseId) {
        for (Map<String, Object> r : snapshots.listReleases()) {
            if (releaseId.equals(r.get("id"))) {
                return r;
            }
        }
        return null;
    }

    private Map<String, Object> symbolView(SymbolRow s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("component", s.componentStableId());
        m.put("stableId", s.stableId());
        m.put("name", s.name());
        m.put("kind", s.kind());
        m.put("boundary", s.boundary());
        m.put("visibility", s.visibility());
        m.put("binding", s.binding());
        m.put("callingConvention", s.callingConvention());
        m.put("variadic", s.variadic());
        m.put("size", s.size());
        m.put("align", s.align());
        m.put("zeroSized", s.zeroSized());
        if (s.record() != null) m.put("record", s.record());
        if (s.function() != null) m.put("function", s.function());
        if (s.enumDetail() != null) m.put("enumDetail", s.enumDetail());
        return m;
    }

    // ---------- errors ----------

    public static class BadRequest extends RuntimeException {
        public BadRequest(String m) {
            super(m);
        }
    }

    @RestControllerAdvice
    public static class Errors {
        @ExceptionHandler(BadRequest.class)
        public ResponseEntity<Map<String, Object>> bad(BadRequest e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }

        @ExceptionHandler(IllegalArgumentException.class)
        public ResponseEntity<Map<String, Object>> illegal(IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }

        @ExceptionHandler(ConflictException.class)
        public ResponseEntity<Map<String, Object>> conflict(ConflictException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", e.getMessage(),
                    "currentRevision", e.currentRev,
                    "conflicts", e.conflicts));
        }
    }
}
