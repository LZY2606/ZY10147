package com.abi.compare;

import com.abi.compare.diff.DiffEngine;
import com.abi.compare.diff.DiffResult;
import com.abi.compare.model.AbiSymbol;
import com.abi.compare.model.AbiType;
import com.abi.compare.model.Extractor;
import com.abi.compare.model.Field;
import com.abi.compare.model.Snapshot;
import com.abi.compare.rule.RuleRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ImpactGraphTest {

    private final DiffEngine engine = new DiffEngine();

    private AbiSymbol sym(String id, String visibility, String... refs) {
        AbiSymbol s = new AbiSymbol();
        s.stableId = id;
        s.name = id;
        s.kind = "FUNCTION";
        s.linkage = "EXPORTED";
        s.visibility = visibility;
        s.callingConvention = "aarch64-aapcs";
        s.variadic = false;
        s.returnType = "void";
        for (String r : refs) {
            s.references.add(r);
        }
        return s;
    }

    @Test
    void changeToHiddenCalleeReachesPublicEntriesAlongGraph() {
        // public A -> hidden B -> changed hidden C ; type used by C changes too
        Snapshot oldSnap = base("1");
        oldSnap.symbols.add(sym("lib.A", "PUBLIC", "lib.B"));
        oldSnap.symbols.add(sym("lib.B", "HIDDEN", "lib.C"));
        AbiSymbol cOld = sym("lib.C", "HIDDEN");
        cOld.parameters.add(new com.abi.compare.model.Parameter("p", "lib.Cfg"));
        oldSnap.symbols.add(cOld);

        Snapshot newSnap = base("2");
        newSnap.symbols.add(sym("lib.A", "PUBLIC", "lib.B"));
        newSnap.symbols.add(sym("lib.B", "HIDDEN", "lib.C"));
        AbiSymbol cNew = sym("lib.C", "HIDDEN");
        cNew.parameters.add(new com.abi.compare.model.Parameter("p", "lib.Cfg2"));
        newSnap.symbols.add(cNew);

        DiffResult result = engine.compare(oldSnap, newSnap,
                new RuleRegistry().all().get("linux-aarch64-public"));
        Set<String> affected = result.affectedPublicEntries.stream()
                .map(e -> e.stableId).collect(Collectors.toSet());
        assertTrue(affected.contains("lib.A"),
                "public entry A is affected through B -> C");
        assertFalse(affected.contains("lib.B"), "hidden callee is not itself a public entry");

        Map<String, java.util.List<java.util.List<String>>> paths =
                result.affectedPublicEntries.stream()
                        .collect(Collectors.toMap(e -> e.stableId, e -> e.impactPaths));
        assertTrue(paths.get("lib.A").stream()
                .anyMatch(p -> p.contains("lib.C") && p.contains("lib.B") && p.contains("lib.A")));
    }

    private Snapshot base(String release) {
        Snapshot s = new Snapshot();
        s.component = "lib";
        s.release = release;
        s.platform = "linux";
        s.arch = "aarch64";
        s.extractor = new Extractor("x", "1");
        return s;
    }
}
