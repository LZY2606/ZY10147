package com.abi.compare.diff;

import com.abi.compare.model.AbiSymbol;
import com.abi.compare.model.AbiType;
import com.abi.compare.model.Decision;
import com.abi.compare.model.Models;
import com.abi.compare.model.Snapshot;
import com.abi.compare.rule.RuleSet;
import com.abi.compare.rule.Severity;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Compares two snapshots under one {@link RuleSet}.
 *
 * <p>Pairing rules:
 * <ul>
 *   <li>symbols/types pair only on explicit stable_id equality;</li>
 *   <li>name divergence of a paired id is a rename, never guessed for
 *       unpaired names (no edit-distance matching);</li>
 *   <li>unpaired entries are plain additions/removals.</li>
 * </ul>
 */
@Component
public class DiffEngine {

    public DiffResult compare(Snapshot oldSnap, Snapshot newSnap, RuleSet rules) {
        Map<String, AbiSymbol> oldSymbols = index(oldSnap.symbols);
        Map<String, AbiSymbol> newSymbols = index(newSnap.symbols);
        Map<String, AbiType> oldTypes = indexTypes(oldSnap.types);
        Map<String, AbiType> newTypes = indexTypes(newSnap.types);

        List<EntryChange> symbolEntries = buildSymbolEntries(oldSymbols, newSymbols, rules);
        List<EntryChange> typeEntries = buildTypeEntries(oldTypes, newTypes, rules);

        Set<String> changedSymbolIds = symbolEntries.stream()
                .filter(e -> !e.changes.isEmpty())
                .map(e -> e.stableId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> changedTypeIds = typeEntries.stream()
                .filter(e -> !e.changes.isEmpty())
                .map(e -> e.stableId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        ImpactAnalyzer impact = new ImpactAnalyzer(
                oldSymbols, newSymbols, oldTypes, newTypes);
        Map<String, List<List<String>>> affected =
                impact.affectedEntries(changedSymbolIds, changedTypeIds);
        annotateImpactPaths(symbolEntries, affected);

        List<EntryChange> affectedEntries = buildAffectedEntries(
                affected, oldSymbols, newSymbols);

        DiffResult result = new DiffResult();
        result.oldSnapshotHash = oldSnap.contentHash;
        result.newSnapshotHash = newSnap.contentHash;
        result.component = newSnap.component;
        result.oldRelease = oldSnap.release;
        result.newRelease = newSnap.release;
        result.platform = newSnap.platform;
        result.arch = newSnap.arch;
        result.ruleSetId = rules.id;
        result.ruleSetVersion = rules.version;
        result.createdAt = Instant.now().toString();
        result.symbols = symbolEntries;
        result.types = typeEntries;
        result.affectedPublicEntries = affectedEntries;
        result.counts = countSeverities(symbolEntries, typeEntries);
        return result;
    }

    private List<EntryChange> buildSymbolEntries(Map<String, AbiSymbol> oldSymbols,
                                                 Map<String, AbiSymbol> newSymbols,
                                                 RuleSet rules) {
        List<EntryChange> entries = new ArrayList<>();
        Set<String> allIds = new LinkedHashSet<>();
        allIds.addAll(oldSymbols.keySet());
        allIds.addAll(newSymbols.keySet());
        for (String id : allIds) {
            AbiSymbol oldS = oldSymbols.get(id);
            AbiSymbol newS = newSymbols.get(id);
            EntryChange entry = new EntryChange();
            entry.stableId = id;
            if (oldS == null) {
                entry.category = ChangeKind.SYMBOL_ADDED;
                entry.newName = newS.name;
                entry.kind = newS.kind;
                entry.linkage = newS.linkage;
                entry.visibility = newS.visibility;
                entry.changes.add(new Change(ChangeKind.SYMBOL_ADDED,
                        rules.severityFor(ChangeKind.SYMBOL_ADDED, Severity.COMPATIBLE).name(),
                        "new exported/imported symbol"));
            } else if (newS == null) {
                entry.category = ChangeKind.SYMBOL_REMOVED;
                entry.oldName = oldS.name;
                entry.kind = oldS.kind;
                entry.linkage = oldS.linkage;
                entry.visibility = oldS.visibility;
                Severity severity = Models.LINK_IMPORTED.equals(oldS.linkage)
                        ? Severity.WARNING
                        : rules.severityFor(ChangeKind.SYMBOL_REMOVED, Severity.BREAKING);
                entry.changes.add(new Change(ChangeKind.SYMBOL_REMOVED, severity.name(),
                        "symbol no longer present (no stable_id match in new snapshot)"));
            } else {
                entry.category = "SYMBOL_CHANGED";
                entry.oldName = oldS.name;
                entry.newName = newS.name;
                entry.kind = newS.kind;
                entry.linkage = newS.linkage;
                entry.visibility = newS.visibility;
                entry.changes = SymbolComparator.compare(oldS, newS, rules);
            }
            entries.add(entry);
        }
        return entries;
    }

    private List<EntryChange> buildTypeEntries(Map<String, AbiType> oldTypes,
                                               Map<String, AbiType> newTypes,
                                               RuleSet rules) {
        List<EntryChange> entries = new ArrayList<>();
        Set<String> allIds = new LinkedHashSet<>();
        allIds.addAll(oldTypes.keySet());
        allIds.addAll(newTypes.keySet());
        for (String id : allIds) {
            AbiType oldT = oldTypes.get(id);
            AbiType newT = newTypes.get(id);
            EntryChange entry = new EntryChange();
            entry.stableId = id;
            if (oldT == null) {
                entry.category = ChangeKind.TYPE_ADDED;
                entry.newName = newT.name;
                entry.kind = newT.kind;
                entry.changes.add(new Change(ChangeKind.TYPE_ADDED,
                        rules.severityFor(ChangeKind.TYPE_ADDED, Severity.COMPATIBLE).name(),
                        "new type"));
            } else if (newT == null) {
                entry.category = ChangeKind.TYPE_REMOVED;
                entry.oldName = oldT.name;
                entry.kind = oldT.kind;
                entry.changes.add(new Change(ChangeKind.TYPE_REMOVED,
                        rules.severityFor(ChangeKind.TYPE_REMOVED, Severity.BREAKING).name(),
                        "type removed"));
            } else {
                entry.category = "TYPE_CHANGED";
                entry.oldName = oldT.name;
                entry.newName = newT.name;
                entry.kind = newT.kind;
                entry.changes = TypeComparator.compare(oldT, newT, rules);
            }
            entries.add(entry);
        }
        return entries;
    }

    private void annotateImpactPaths(List<EntryChange> symbolEntries,
                                     Map<String, List<List<String>>> affected) {
        for (EntryChange entry : symbolEntries) {
            List<List<String>> paths = affected.get(entry.stableId);
            if (paths != null) {
                entry.impactPaths = paths;
            }
        }
    }

    private List<EntryChange> buildAffectedEntries(Map<String, List<List<String>>> affected,
                                                   Map<String, AbiSymbol> oldSymbols,
                                                   Map<String, AbiSymbol> newSymbols) {
        List<EntryChange> result = new ArrayList<>();
        for (Map.Entry<String, List<List<String>>> e : affected.entrySet()) {
            AbiSymbol s = newSymbols.getOrDefault(e.getKey(), oldSymbols.get(e.getKey()));
            if (s == null) {
                continue;
            }
            EntryChange entry = new EntryChange();
            entry.category = "IMPACTED_PUBLIC_ENTRY";
            entry.stableId = e.getKey();
            entry.newName = s.name;
            entry.oldName = oldSymbols.containsKey(e.getKey()) ? oldSymbols.get(e.getKey()).name : null;
            entry.kind = s.kind;
            entry.linkage = s.linkage;
            entry.visibility = s.visibility;
            entry.impactPaths = e.getValue();
            result.add(entry);
        }
        return result;
    }

    private Map<String, Integer> countSeverities(List<EntryChange> symbols,
                                                 List<EntryChange> types) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("BREAKING", 0);
        counts.put("WARNING", 0);
        counts.put("COMPATIBLE", 0);
        counts.put("INFO", 0);
        counts.put("entriesWithChanges", 0);
        for (List<EntryChange> group : List.of(symbols, types)) {
            for (EntryChange entry : group) {
                if (!entry.changes.isEmpty()) {
                    counts.merge("entriesWithChanges", 1, Integer::sum);
                }
                for (Change change : entry.changes) {
                    counts.merge(change.severity, 1, Integer::sum);
                }
            }
        }
        return counts;
    }

    private Map<String, AbiSymbol> index(List<AbiSymbol> symbols) {
        Map<String, AbiSymbol> map = new LinkedHashMap<>();
        if (symbols != null) {
            for (AbiSymbol s : symbols) {
                if (s.stableId == null) {
                    throw new IllegalArgumentException(
                            "symbol without stable_id: " + s.name + " (correspondence needs explicit id)");
                }
                map.put(s.stableId, s);
            }
        }
        return map;
    }

    private Map<String, AbiType> indexTypes(List<AbiType> types) {
        Map<String, AbiType> map = new LinkedHashMap<>();
        if (types != null) {
            for (AbiType t : types) {
                if (t.stableId == null) {
                    throw new IllegalArgumentException(
                            "type without stable_id: " + t.name);
                }
                map.put(t.stableId, t);
            }
        }
        return map;
    }
}
