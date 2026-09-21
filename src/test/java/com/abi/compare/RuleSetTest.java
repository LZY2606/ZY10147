package com.abi.compare;

import com.abi.compare.diff.DiffEngine;
import com.abi.compare.diff.DiffResult;
import com.abi.compare.model.AbiType;
import com.abi.compare.model.Extractor;
import com.abi.compare.model.Field;
import com.abi.compare.model.Snapshot;
import com.abi.compare.rule.RuleRegistry;
import com.abi.compare.rule.RuleSet;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RuleSetTest {

    private final RuleRegistry registry = new RuleRegistry();
    private final DiffEngine engine = new DiffEngine();

    private Snapshot snap(String release, boolean withPrivateTail) {
        Snapshot s = new Snapshot();
        s.component = "lib";
        s.release = release;
        s.platform = "linux";
        s.arch = "aarch64";
        s.extractor = new Extractor("x", "1");
        AbiType t = new AbiType();
        t.stableId = "lib.T";
        t.kind = "STRUCT";
        t.name = "T";
        t.alignment = 8;
        Field a = new Field();
        a.name = "a";
        a.type = "u64";
        a.offset = 0;
        a.size = 8;
        t.fields.add(a);
        if (withPrivateTail) {
            Field p = new Field();
            p.name = "priv";
            p.type = "u64";
            p.offset = 8;
            p.size = 8;
            p.privateField = true;
            t.fields.add(p);
            t.size = 16;
            t.tailPadding = 0L;
        } else {
            t.size = 8;
            t.tailPadding = 0L;
        }
        s.types.add(t);
        return s;
    }

    @Test
    void sameSnapshotsYieldDifferentVerdictsUnderDifferentRuleSets() {
        DiffResult publicV1 = engine.compare(snap("1", false), snap("2", true),
                registry.all().get("linux-aarch64-public"));
        engine.compare(snap("1", false), snap("2", true),
                registry.all().get("linux-aarch64-public-v2"));
        DiffResult priv = engine.compare(snap("1", false), snap("2", true),
                registry.all().get("linux-aarch64-private"));
        DiffResult windows = engine.compare(snap("1", false), snap("2", true),
                registry.all().get("windows-amd64-public"));

        Map<String, String> v1Severity = severityOf(publicV1, "FIELD_APPENDED_PRIVATE_TAIL:priv");
        Map<String, String> winSeverity = severityOf(windows, "FIELD_APPENDED_PRIVATE_TAIL:priv");
        assertEquals("WARNING", v1Severity.get("priv"));
        assertEquals("BREAKING", winSeverity.get("priv"),
                "windows public rule set treats sizeof growth as breaking");
        assertEquals("COMPATIBLE",
                severityOf(priv, "FIELD_APPENDED_PRIVATE_TAIL:priv").get("priv"),
                "private boundary tolerates the change");
    }

    @Test
    void ruleVersionEvolutionChangesPaddingReuseVerdict() {
        RuleSet v1 = registry.all().get("linux-aarch64-public");
        RuleSet v2 = registry.all().get("linux-aarch64-public-v2");
        assertEquals("1.0", v1.version);
        assertEquals("2.0", v2.version);
        assertEquals(com.abi.compare.rule.Severity.WARNING,
                v1.severities.get("PADDING_REUSED"));
        assertEquals(com.abi.compare.rule.Severity.BREAKING,
                v2.severities.get("PADDING_REUSED"));
    }

    private Map<String, String> severityOf(DiffResult result, String keyPrefix) {
        return result.types.get(0).changes.stream()
                .filter(c -> (c.kind + ":" + c.member).equals(keyPrefix))
                .collect(java.util.stream.Collectors.toMap(
                        c -> c.member, c -> c.severity, (a, b) -> a));
    }
}
