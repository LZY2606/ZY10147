package com.example.abidiff.diff;

import com.example.abidiff.model.Enums.AliasKind;
import com.example.abidiff.model.StoredTypes.AliasRow;
import com.example.abidiff.model.StoredTypes.LoadedSnapshot;
import com.example.abidiff.model.StoredTypes.SymbolRow;

import java.util.*;

/**
 * Indexes one snapshot for matching. Matching keys are, in order of trust:
 * <ol>
 *   <li>explicit {@code stable_id};</li>
 *   <li>explicit alias ({@code ALIAS} / {@code WEAK_ALIAS} / {@code PLATFORM_NAME}).</li>
 * </ol>
 * Edit distance or fuzzy name similarity is intentionally absent: without
 * evidence a new+removed pair is reported as add+remove, never as a rename.
 */
public final class SymbolIndex {

    private final Map<String, SymbolRow> byStable = new HashMap<>();
    private final Map<String, String> aliasToStable = new HashMap<>();
    private final Map<String, Set<String>> stableToAliases = new HashMap<>();

    public SymbolIndex(LoadedSnapshot snap) {
        for (SymbolRow s : snap.symbols()) {
            byStable.put(s.stableId(), s);
        }
        for (AliasRow a : snap.aliases()) {
            if (a.stableId() == null) {
                continue;
            }
            if (a.kind() == AliasKind.EXPLICIT || a.kind() == AliasKind.WEAK_ALIAS
                    || a.kind() == AliasKind.PLATFORM_NAME) {
                aliasToStable.put(a.alias(), a.stableId());
                stableToAliases.computeIfAbsent(a.stableId(), k -> new HashSet<>()).add(a.alias());
            }
        }
    }

    public SymbolRow byStable(String stableId) {
        return byStable.get(stableId);
    }

    public Collection<SymbolRow> all() {
        return byStable.values();
    }

    /** Resolve a name (either stable_id or a declared alias) to stable_id. */
    public String resolve(String name) {
        if (byStable.containsKey(name)) {
            return name;
        }
        return aliasToStable.get(name);
    }

    public Set<String> aliasesOf(String stableId) {
        return stableToAliases.getOrDefault(stableId, Set.of());
    }
}
