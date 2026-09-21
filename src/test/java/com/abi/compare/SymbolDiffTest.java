package com.abi.compare;

import com.abi.compare.diff.Change;
import com.abi.compare.diff.DiffEngine;
import com.abi.compare.diff.DiffResult;
import com.abi.compare.diff.EntryChange;
import com.abi.compare.model.AbiSymbol;
import com.abi.compare.model.Extractor;
import com.abi.compare.model.Parameter;
import com.abi.compare.model.Snapshot;
import com.abi.compare.rule.RuleRegistry;
import com.abi.compare.rule.RuleSet;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class SymbolDiffTest {

    private final RuleSet rules = new RuleRegistry().all().get("linux-aarch64-public");
    private final DiffEngine engine = new DiffEngine();

    private Snapshot snap(String release, AbiSymbol... symbols) {
        Snapshot s = new Snapshot();
        s.schemaVersion = 1;
        s.component = "lib";
        s.release = release;
        s.platform = "linux";
        s.arch = "aarch64";
        s.extractor = new Extractor("readelf-abi", "2.1");
        for (AbiSymbol sym : symbols) {
            s.symbols.add(sym);
        }
        return s;
    }

    private AbiSymbol symbol(String id, String name, String conv, Boolean variadic) {
        AbiSymbol s = new AbiSymbol();
        s.stableId = id;
        s.name = name;
        s.kind = "FUNCTION";
        s.linkage = "EXPORTED";
        s.visibility = "PUBLIC";
        s.callingConvention = conv;
        s.variadic = variadic;
        s.returnType = "void";
        return s;
    }

    private Map<String, EntryChange> byId(DiffResult result) {
        return result.symbols.stream()
                .collect(Collectors.toMap(e -> e.stableId, Function.identity()));
    }

    @Test
    void renameOnlyAllowedViaStableId() {
        AbiSymbol oldS = symbol("lib.fn", "old_mangled", "aarch64-aapcs", false);
        AbiSymbol newS = symbol("lib.fn", "new_mangled", "aarch64-aapcs", false);
        DiffResult result = engine.compare(snap("1", oldS), snap("2", newS), rules);
        EntryChange entry = byId(result).get("lib.fn");
        assertEquals("SYMBOL_CHANGED", entry.category);
        assertTrue(entry.changes.stream().anyMatch(c -> c.kind.equals("SYMBOL_RENAMED")));
        ChangeRenameHasEvidence(entry);
    }

    private void ChangeRenameHasEvidence(EntryChange entry) {
        assertTrue(entry.changes.stream()
                .filter(c -> c.kind.equals("SYMBOL_RENAMED"))
                .findFirst().orElseThrow().evidence.contains("stable_id"));
    }

    @Test
    void nameSimilarityWithoutStableIdIsNeverARename() {
        AbiSymbol oldS = symbol("lib.alpha", "widget_create_ex", "aarch64-aapcs", false);
        AbiSymbol newS = symbol("lib.beta", "widget_create_ez", "aarch64-aapcs", false);
        DiffResult result = engine.compare(snap("1", oldS), snap("2", newS), rules);
        Map<String, EntryChange> entries = byId(result);
        assertEquals("SYMBOL_REMOVED", entries.get("lib.alpha").category);
        assertEquals("SYMBOL_ADDED", entries.get("lib.beta").category);
        assertNull(entries.get("lib.alpha").newName,
                "no edit-distance rename inference without stable_id evidence");
    }

    @Test
    void callingConventionAndVariadicAreBreakingExplicitStates() {
        DiffResult result = engine.compare(
                snap("1", symbol("lib.fn", "fn", "ms", true)),
                snap("2", symbol("lib.fn", "fn", "stdcall", false)), rules);
        Map<String, Change> kinds = result.symbols.get(0).changes.stream()
                .collect(Collectors.toMap(c -> c.kind, c -> c, (a, b) -> a));
        assertEquals("BREAKING", kinds.get("CALLING_CONVENTION_CHANGED").severity);
        assertEquals("BREAKING", kinds.get("VARIADIC_CHANGED").severity);
    }

    @Test
    void aliasAddAndRemoveAreDistinct() {
        AbiSymbol oldS = symbol("lib.fn", "fn", "aarch64-aapcs", false);
        oldS.aliases.add("old_alias");
        AbiSymbol newS = symbol("lib.fn", "fn", "aarch64-aapcs", false);
        newS.aliases.add("new_alias");
        Map<String, Change> kinds = engine
                .compare(snap("1", oldS), snap("2", newS), rules)
                .symbols.get(0).changes.stream()
                .collect(Collectors.toMap(c -> c.kind + ":" + c.member, c -> c));
        assertEquals("INFO", kinds.get("ALIAS_ADDED:new_alias").severity);
        assertEquals("WARNING", kinds.get("ALIAS_REMOVED:old_alias").severity);
    }

    @Test
    void parameterCountAndTypeChangesBreak() {
        AbiSymbol oldS = symbol("lib.fn", "fn", "aarch64-aapcs", false);
        oldS.parameters.add(new Parameter("a", "u32"));
        AbiSymbol newS = symbol("lib.fn", "fn", "aarch64-aapcs", false);
        newS.parameters.add(new Parameter("a", "u64"));
        newS.parameters.add(new Parameter("b", "u32"));
        Map<String, Change> kinds = engine
                .compare(snap("1", oldS), snap("2", newS), rules)
                .symbols.get(0).changes.stream()
                .collect(Collectors.toMap(c -> c.kind + ":" + (c.member == null ? "" : c.member),
                        c -> c, (a, b) -> a));
        assertTrue(kinds.containsKey("PARAMETER_CHANGED:"));
        assertEquals("BREAKING", kinds.get("PARAMETER_CHANGED:a").severity);
    }

}
