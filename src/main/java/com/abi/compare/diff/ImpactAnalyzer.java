package com.abi.compare.diff;

import com.abi.compare.model.AbiSymbol;
import com.abi.compare.model.AbiType;
import com.abi.compare.model.Models;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Walks the symbol reference graph from each change to the public entry
 * points that may be impacted.
 *
 * <p>Edges are extracted from both releases so removed symbols and their
 * callers are still traversable.
 */
public class ImpactAnalyzer {

    private final Map<String, AbiSymbol> unionSymbols;
    private final Map<String, AbiType> unionTypes;
    /** reverse dependency: stableId -> stableIds that reference it. */
    private final Map<String, Set<String>> reverse = new HashMap<>();

    public ImpactAnalyzer(Map<String, AbiSymbol> oldSymbols, Map<String, AbiSymbol> newSymbols,
                          Map<String, AbiType> oldTypes, Map<String, AbiType> newTypes) {
        this.unionSymbols = new LinkedHashMap<>(oldSymbols);
        newSymbols.forEach(unionSymbols::putIfAbsent);
        this.unionTypes = new LinkedHashMap<>(oldTypes);
        newTypes.forEach(unionTypes::putIfAbsent);
        buildEdges(oldSymbols);
        buildEdges(newSymbols);
        buildTypeUsageEdges(oldSymbols);
        buildTypeUsageEdges(newSymbols);
    }

    private void buildEdges(Map<String, AbiSymbol> symbols) {
        for (AbiSymbol s : symbols.values()) {
            if (s.references == null) {
                continue;
            }
            for (String ref : s.references) {
                reverse.computeIfAbsent(ref, k -> new LinkedHashSet<>()).add(s.stableId);
            }
        }
    }

    private void buildTypeUsageEdges(Map<String, AbiSymbol> symbols) {
        for (AbiSymbol s : symbols.values()) {
            Set<String> usedTypes = new LinkedHashSet<>();
            if (s.returnType != null) {
                usedTypes.add(s.returnType);
            }
            if (s.parameters != null) {
                s.parameters.forEach(p -> usedTypes.add(p.type));
            }
            for (String typeId : usedTypes) {
                if (typeId != null) {
                    reverse.computeIfAbsent("type:" + typeId, k -> new LinkedHashSet<>())
                            .add(s.stableId);
                }
            }
        }
    }

    private boolean isPublicEntry(AbiSymbol s) {
        if (s == null || !Models.LINK_EXPORTED.equals(s.linkage)) {
            return false;
        }
        return s.visibility == null || Models.VIS_PUBLIC.equals(s.visibility)
                || Models.VIS_PROTECTED.equals(s.visibility);
    }

    /**
     * For each changed node find public exported symbols reachable through
     * reverse references, recording simple paths (capped for readability).
     *
     * @param changedSymbolIds symbol stable ids that changed themselves
     * @param changedTypeIds   type stable ids that changed themselves
     * @return public entry stableId -> one or more impact paths
     */
    public Map<String, List<List<String>>> affectedEntries(Set<String> changedSymbolIds,
                                                           Set<String> changedTypeIds) {
        Map<String, List<List<String>>> result = new LinkedHashMap<>();
        Set<String> roots = new LinkedHashSet<>(changedSymbolIds);
        changedTypeIds.forEach(t -> roots.add("type:" + t));
        for (String root : roots) {
            List<List<String>> foundPaths = new ArrayList<>();
            Set<String> visited = new HashSet<>();
            List<String> path = new ArrayList<>();
            path.add(root);
            dfs(root, visited, path, foundPaths, 0);
            for (List<String> p : foundPaths) {
                String entry = p.get(p.size() - 1);
                result.computeIfAbsent(entry, k -> new ArrayList<>()).add(p);
            }
        }
        return result;
    }

    private void dfs(String node, Set<String> visited, List<String> path,
                     List<List<String>> foundPaths, int depth) {
        if (depth > 8 || foundPaths.size() > 24) {
            return;
        }
        if (isPublicEntry(unionSymbols.get(node))) {
            foundPaths.add(new ArrayList<>(path));
        }
        Set<String> callers = reverse.get(node);
        if (callers == null) {
            return;
        }
        for (String caller : callers) {
            if (visited.contains(caller)) {
                continue;
            }
            visited.add(caller);
            path.add(caller);
            dfs(caller, visited, path, foundPaths, depth + 1);
            path.remove(path.size() - 1);
        }
    }

}
