package com.example.abidiff.diff;

import com.example.abidiff.model.Enums.Category;
import com.example.abidiff.model.StoredTypes.LoadedSnapshot;
import com.example.abidiff.model.StoredTypes.SymbolRow;

import java.util.*;

/**
 * Produces raw, rule-independent {@link Finding}s for a pair of snapshots.
 * Severity is a neutral default here; the rule set maps change kinds to
 * platform/boundary-specific severities afterwards.
 */
public final class DiffEngine {

    private DiffEngine() {
    }

    public record Result(List<Finding> findings, List<RenameEvidence> renameEvidence) {
    }

    /** An evidence-backed rename claim. */
    public record RenameEvidence(String leftStableId, String rightStableId,
                                 String leftName, String rightName, String evidence) {
    }

    public static Result diff(LoadedSnapshot left, LoadedSnapshot right) {
        SymbolIndex li = new SymbolIndex(left);
        SymbolIndex ri = new SymbolIndex(right);
        ImpactGraph rgraph = new ImpactGraph(right);

        List<Finding> findings = new ArrayList<>();
        List<RenameEvidence> renames = new ArrayList<>();

        // Stable-id matching is the identity contract.
        Set<String> common = new HashSet<>();
        for (SymbolRow ls : li.all()) {
            SymbolRow rs = ri.byStable(ls.stableId());
            if (rs != null) {
                common.add(ls.stableId());
            }
        }

        // Rename detection requires explicit evidence (alias in either snapshot
        // pointing to the stable id). Fuzzy/name-similarity matching is forbidden.
        Map<String, String> renamedRightStableByLeft = new HashMap<>();
        for (SymbolRow ls : li.all()) {
            if (common.contains(ls.stableId())) {
                continue;
            }
            String evidence = renameEvidence(ls.stableId(), ls.name(), li, ri, common);
            if (evidence != null) {
                SymbolRow target = ri.byStable(evidenceStableId(ls, li, ri));
                if (target != null && !common.contains(target.stableId())
                        && renamedRightStableByLeft.values().stream().noneMatch(target.stableId()::equals)) {
                    renamedRightStableByLeft.put(ls.stableId(), target.stableId());
                    common.add(target.stableId());
                    renames.add(new RenameEvidence(ls.stableId(), target.stableId(),
                            ls.name(), target.name(), evidence));
                    findings.add(find(ls, target, "SYMBOL_RENAMED", Category.RENAMED,
                            "symbol renamed " + ls.name() + " -> " + target.name()
                                    + " (evidence: " + evidence + ")",
                            map("oldName", ls.name(), "newName", target.name(), "evidence", evidence),
                            rgraph));
                }
            }
        }

        for (SymbolRow ls : li.all()) {
            SymbolRow rs = ri.byStable(ls.stableId());
            if (rs == null) {
                if (!renamedRightStableByLeft.containsKey(ls.stableId())) {
                    findings.add(find(ls, null, "SYMBOL_REMOVED", Category.REMOVED,
                            "symbol removed: " + ls.name(),
                            map("name", ls.name()), rgraph));
                }
            } else {
                compareSymbol(ls, rs, findings, rgraph);
            }
        }
        for (SymbolRow rs : ri.all()) {
            if (li.byStable(rs.stableId()) == null
                    && !renamedRightStableByLeft.containsValue(rs.stableId())) {
                findings.add(find(null, rs, "SYMBOL_ADDED", Category.ADDED,
                        "symbol added: " + rs.name(),
                        map("name", rs.name()), rgraph));
            }
        }
        return new Result(findings, renames);
    }

    /**
     * Evidence exists iff the left snapshot's stable id is declared under the
     * right snapshot's name as an alias (or vice versa), or the same stable id
     * is explicitly associated with both names. Returns the evidence label.
     */
    private static String renameEvidence(String stableId, String leftName,
                                         SymbolIndex li, SymbolIndex ri, Set<String> common) {
        // stable_id already exists on the right -> ordinary match, not rename
        if (ri.byStable(stableId) != null) {
            return null;
        }
        // The right side carries an alias whose target stable id exists only on
        // the left, using the left name: explicit cross-name mapping.
        for (SymbolRow candidate : ri.all()) {
            if (li.byStable(candidate.stableId()) != null) {
                continue; // already matched
            }
            Set<String> aliases = ri.aliasesOf(candidate.stableId());
            if (aliases.contains(leftName) || aliases.contains(stableId)) {
                return "explicit alias declared on right snapshot";
            }
        }
        for (String alias : li.aliasesOf(stableId)) {
            String resolved = ri.resolve(alias);
            if (resolved != null && li.byStable(resolved) == null) {
                return "explicit alias declared on left snapshot";
            }
        }
        return null;
    }

    private static String evidenceStableId(SymbolRow ls, SymbolIndex li, SymbolIndex ri) {
        for (SymbolRow candidate : ri.all()) {
            if (li.byStable(candidate.stableId()) == null) {
                Set<String> aliases = ri.aliasesOf(candidate.stableId());
                if (aliases.contains(ls.name()) || aliases.contains(ls.stableId())) {
                    return candidate.stableId();
                }
            }
        }
        for (String alias : li.aliasesOf(ls.stableId())) {
            String resolved = ri.resolve(alias);
            if (resolved != null && li.byStable(resolved) == null) {
                return resolved;
            }
        }
        return null;
    }

    private static void compareSymbol(SymbolRow a, SymbolRow b, List<Finding> out, ImpactGraph graph) {
        if (!Objects.equals(a.visibility(), b.visibility())) {
            boolean reduced = visibilityRank(b.visibility()) < visibilityRank(a.visibility());
            String kind = reduced ? "VISIBILITY_REDUCED" : "VISIBILITY_RAISED";
            out.add(find(a, b, kind, Category.VISIBILITY,
                    "visibility " + a.visibility() + " -> " + b.visibility(),
                    map("before", a.visibility(), "after", b.visibility()), graph));
        }
        if (!Objects.equals(a.binding(), b.binding()) && "WEAK".equals(a.binding())
                && !"WEAK".equals(b.binding())) {
            out.add(find(a, b, "VISIBILITY_REDUCED", Category.VISIBILITY,
                    "weak binding removed: " + a.name(),
                    map("before", a.binding(), "after", b.binding()), graph));
        }

        boolean za = a.zeroSized() || (a.size() != null && a.size() == 0);
        boolean zb = b.zeroSized() || (b.size() != null && b.size() == 0);
        if (za != zb) {
            out.add(find(a, b, za ? "ZST_TO_SIZED" : "SIZED_TO_ZST", Category.LAYOUT,
                    (za ? "zero-sized type became sized" : "type became zero-sized") + ": " + b.name(),
                    map("sizeBefore", a.size(), "sizeAfter", b.size(),
                            "zeroBefore", za, "zeroAfter", zb), graph));
        }

        switch (a.kind()) {
            case "FUNCTION" -> compareFunction(a, b, out, graph);
            case "VARIABLE" -> {
                if (!Objects.equals(typeOf(a), typeOf(b))) {
                    out.add(find(a, b, "VAR_TYPE_CHANGED", Category.SIGNATURE,
                            "variable type changed: " + a.name(),
                            map("before", typeOf(a), "after", typeOf(b)), graph));
                }
                if (!za && !zb && !Objects.equals(a.size(), b.size())) {
                    out.add(find(a, b, "LAYOUT_SIZE_CHANGED", Category.LAYOUT,
                            "variable size changed: " + a.name(),
                            map("sizeBefore", a.size(), "sizeAfter", b.size()), graph));
                }
            }
            case "TYPE_RECORD" -> {
                for (LayoutDiffer.RawChange rc : LayoutDiffer.compare(a, b)) {
                    out.add(find(a, b, rc.kind(), Category.LAYOUT, rc.title(), rc.detail(), graph));
                }
            }
            case "TYPE_ENUM" -> compareEnum(a, b, out, graph);
            default -> {
                // TYPEDEF: only the aliased type matters, exposed as type change
                if (!Objects.equals(typeOf(a), typeOf(b))) {
                    out.add(find(a, b, "VAR_TYPE_CHANGED", Category.SIGNATURE,
                            "typedef target changed: " + a.name(),
                            map("before", typeOf(a), "after", typeOf(b)), graph));
                }
            }
        }
    }

    private static void compareFunction(SymbolRow a, SymbolRow b, List<Finding> out, ImpactGraph graph) {
        if (!Objects.equals(a.callingConvention(), b.callingConvention())) {
            out.add(find(a, b, "CC_CHANGED", Category.SIGNATURE,
                    "calling convention " + cc(a) + " -> " + cc(b) + " for " + a.name(),
                    map("before", cc(a), "after", cc(b)), graph));
        }
        if (a.variadic() != b.variadic()) {
            out.add(find(a, b, "CC_VARARGS_TOGGLED", Category.SIGNATURE,
                    "variadic flag " + a.variadic() + " -> " + b.variadic() + " for " + a.name(),
                    map("before", a.variadic(), "after", b.variadic()), graph));
        }
        var fa = a.function() == null ? null : a.function();
        var fb = b.function() == null ? null : b.function();
        if (fa != null && fb != null) {
            if (!Objects.equals(fa.return_type(), fb.return_type())) {
                out.add(find(a, b, "RETURN_TYPE_CHANGED", Category.SIGNATURE,
                        "return type " + fa.return_type() + " -> " + fb.return_type(),
                        map("before", fa.return_type(), "after", fb.return_type()), graph));
            }
            int n = Math.max(fa.params().size(), fb.params().size());
            for (int i = 0; i < n; i++) {
                var pa = i < fa.params().size() ? fa.params().get(i) : null;
                var pb = i < fb.params().size() ? fb.params().get(i) : null;
                if (pa == null) {
                    out.add(find(a, b, "PARAM_ADDED", Category.SIGNATURE,
                            "parameter added at position " + (i + 1) + ": " + pb.type() + " " + pb.name(),
                            map("position", i + 1, "type", pb.type(), "name", pb.name()), graph));
                } else if (pb == null) {
                    out.add(find(a, b, "PARAM_REMOVED", Category.SIGNATURE,
                            "parameter removed at position " + (i + 1) + ": " + pa.type() + " " + pa.name(),
                            map("position", i + 1, "type", pa.type(), "name", pa.name()), graph));
                } else if (!Objects.equals(pa.type(), pb.type())) {
                    out.add(find(a, b, "PARAM_TYPE_CHANGED", Category.SIGNATURE,
                            "parameter " + pa.name() + " type " + pa.type() + " -> " + pb.type(),
                            map("position", i + 1, "name", pa.name(), "before", pa.type(), "after", pb.type()), graph));
                }
            }
        }
    }

    private static void compareEnum(SymbolRow a, SymbolRow b, List<Finding> out, ImpactGraph graph) {
        var ea = a.enumDetail();
        var eb = b.enumDetail();
        if (ea == null || eb == null) {
            return;
        }
        if (!Objects.equals(ea.underlying_type(), eb.underlying_type())
                || !Objects.equals(a.size(), b.size())) {
            out.add(find(a, b, "LAYOUT_SIZE_CHANGED", Category.LAYOUT,
                    "enum underlying type/size changed: " + a.name(),
                    map("underlyingBefore", ea.underlying_type(), "underlyingAfter", eb.underlying_type(),
                            "sizeBefore", a.size(), "sizeAfter", b.size()), graph));
        }
    }

    private static String typeOf(SymbolRow s) {
        if (s.function() != null) {
            return s.function().return_type();
        }
        if (s.enumDetail() != null) {
            return s.enumDetail().underlying_type();
        }
        return null;
    }

    private static String cc(SymbolRow s) {
        return s.callingConvention() == null ? "C" : s.callingConvention();
    }

    private static int visibilityRank(String v) {
        return switch (v == null ? "DEFAULT" : v) {
            case "HIDDEN", "STATIC_LOCAL" -> 0;
            case "PROTECTED" -> 1;
            case "DEFAULT", "EXPORT" -> 2;
            default -> 1;
        };
    }

    private static Finding find(SymbolRow left, SymbolRow right, String kind, Category category,
                                String title, Map<String, Object> detail, ImpactGraph graph) {
        SymbolRow anchor = right != null ? right : left;
        List<String> roots = graph.affectedRoots(anchor.stableId());
        return new Finding(anchor.componentStableId(), anchor.stableId(), kind, category,
                com.example.abidiff.model.Enums.Severity.INFO, anchor.boundary(),
                title, detail, roots);
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }
}
