package com.example.abidiff.diff;

import com.example.abidiff.model.StoredTypes.LoadedSnapshot;
import com.example.abidiff.model.StoredTypes.RefRow;
import com.example.abidiff.model.StoredTypes.SymbolRow;

import java.util.*;

/**
 * Reverse reference graph of one snapshot. Used to walk from a changed symbol
 * back to public entry points (exported functions/variables) that may be
 * affected by the change.
 */
public final class ImpactGraph {

    /** stable_id -> symbols that reference it (incoming edges). */
    private final Map<String, List<String>> incoming = new HashMap<>();
    private final Map<String, SymbolRow> symbols = new HashMap<>();
    private final List<RefRow> refs = new ArrayList<>();

    public ImpactGraph(LoadedSnapshot snap) {
        for (SymbolRow s : snap.symbols()) {
            symbols.put(s.stableId(), s);
        }
        for (RefRow r : snap.refs()) {
            refs.add(r);
            incoming.computeIfAbsent(r.to(), k -> new ArrayList<>()).add(r.from());
        }
    }

    /**
     * Public entry points reachable from the changed symbol: reverse traversal
     * until a PUBLIC exported function/variable is hit. A changed public symbol
     * is itself an entry point.
     */
    public List<String> affectedRoots(String changedStableId) {
        LinkedHashSet<String> roots = new LinkedHashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        stack.push(changedStableId);
        while (!stack.isEmpty()) {
            String cur = stack.pop();
            if (!seen.add(cur)) {
                continue;
            }
            SymbolRow s = symbols.get(cur);
            if (s != null && isPublicEntry(s) && !cur.equals(changedStableId)) {
                roots.add(cur);
                continue; // consumers above an entry point need not be enumerated
            }
            for (String caller : incoming.getOrDefault(cur, List.of())) {
                stack.push(caller);
            }
        }
        SymbolRow self = symbols.get(changedStableId);
        List<String> out = new ArrayList<>();
        if (self != null && isPublicEntry(self)) {
            out.add(changedStableId);
        }
        roots.stream().sorted().forEach(out::add);
        return out;
    }

    private boolean isPublicEntry(SymbolRow s) {
        if (!"PUBLIC".equals(s.boundary())) {
            return false;
        }
        return switch (s.kind()) {
            case "FUNCTION", "VARIABLE" ->
                    !"HIDDEN".equals(s.visibility()) && !"STATIC_LOCAL".equals(s.visibility());
            default -> false;
        };
    }

    public List<RefRow> refs() {
        return refs;
    }
}
