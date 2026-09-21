package com.abi.compare;

import com.abi.compare.diff.DiffResult;
import com.abi.compare.model.AbiSymbol;
import com.abi.compare.model.AbiType;
import com.abi.compare.model.Decision;
import com.abi.compare.model.Extractor;
import com.abi.compare.model.Field;
import com.abi.compare.model.Parameter;
import com.abi.compare.model.Snapshot;
import com.abi.compare.storage.ComparisonService;
import com.abi.compare.storage.DecisionConflictException;
import com.abi.compare.storage.Repository;
import com.abi.compare.storage.SnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "ABI_DB=target/test-abi.db",
        "spring.sql.init.mode=always"
})
class PersistenceIntegrationTest {

    @Autowired
    SnapshotService snapshotService;
    @Autowired
    ComparisonService comparisonService;
    @Autowired
    Repository repository;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM decisions");
        jdbc.update("DELETE FROM comparisons");
        jdbc.update("DELETE FROM snapshots");
    }

    private Snapshot snapshot(String release, String platform, String arch,
                              boolean extraField, boolean variadic) {
        Snapshot s = new Snapshot();
        s.schemaVersion = 1;
        s.component = "intlib";
        s.release = release;
        s.platform = platform;
        s.arch = arch;
        s.extractor = new Extractor("unit-extractor", "9");

        AbiSymbol fn = new AbiSymbol();
        fn.stableId = "intlib.fn";
        fn.name = "fn_" + platform;
        fn.kind = "FUNCTION";
        fn.linkage = "EXPORTED";
        fn.visibility = "PUBLIC";
        fn.callingConvention = "aarch64-aapcs";
        fn.variadic = variadic;
        fn.returnType = "void";
        fn.parameters.add(new Parameter("p", "intlib.Cfg"));
        s.symbols.add(fn);

        AbiType t = new AbiType();
        t.stableId = "intlib.Cfg";
        t.kind = "STRUCT";
        t.name = "Cfg";
        t.alignment = 8;
        Field f = new Field();
        f.name = "x";
        f.type = "u64";
        f.offset = 0;
        f.size = 8;
        t.fields.add(f);
        if (extraField) {
            Field p = new Field();
            p.name = "y";
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

    private String ingest(String release, String platform, String arch,
                          boolean extra, boolean variadic) {
        return snapshotService.ingest(snapshot(release, platform, arch, extra, variadic))
                .get("contentHash").toString();
    }

    @Test
    void snapshotDedupAtomicCompareAndDecisionLifecycle() {
        String oldHash = ingest("1.0.0", "linux", "aarch64", false, true);
        String newHash = ingest("2.0.0", "linux", "aarch64", true, false);

        // same content de-duplicates
        Map<String, Object> second = snapshotService.ingest(
                snapshot("1.0.0", "linux", "aarch64", false, true));
        assertEquals(oldHash, second.get("contentHash"));
        assertTrue((Boolean) second.get("deduped"));

        // atomic/idempotent comparison
        DiffResult first = comparisonService.compare(oldHash, newHash, "linux-aarch64-public");
        DiffResult again = comparisonService.compare(oldHash, newHash, "linux-aarch64-public");
        assertEquals(first.id, again.id);
        assertEquals(0L, first.version);

        // expired exception for the private tail append
        Decision expired = new Decision();
        expired.stableId = "intlib.Cfg";
        expired.changeKind = "FIELD_APPENDED_PRIVATE_TAIL";
        expired.platform = "*";
        expired.verdict = "ACCEPT";
        expired.rationale = "only valid for the 1.x line";
        expired.effectiveFrom = "1.0.0";
        expired.expiresAfterVersion = "1.9.0";
        expired.baseVersion = 0L;
        comparisonService.decide(first.id, expired);

        DiffResult after = comparisonService.get(first.id);
        assertEquals(1L, after.version);
        // change is not suppressed: exception expired before 2.0.0
        boolean expiredView = after.types.stream()
                .filter(e -> e.stableId.equals("intlib.Cfg"))
                .flatMap(e -> e.decisions.stream())
                .anyMatch(d -> "EXPIRED".equals(d.status));
        assertTrue(expiredView, "exception past expiresAfterVersion is EXPIRED");
        boolean noExceptionRef = after.types.stream()
                .filter(e -> e.stableId.equals("intlib.Cfg"))
                .flatMap(e -> e.changes.stream())
                .filter(c -> c.kind.equals("FIELD_APPENDED_PRIVATE_TAIL:y".split(":")[0]))
                .allMatch(c -> c.exceptionRef == null);
        assertTrue(noExceptionRef, "expired exceptions never annotate changes as accepted");

        // active exception with a wide enough window does annotate the change
        Decision active = new Decision();
        active.stableId = "intlib.Cfg";
        active.changeKind = "FIELD_APPENDED_PRIVATE_TAIL";
        active.platform = "linux";
        active.verdict = "ACCEPT";
        active.rationale = "linux rebuilds all consumers";
        active.effectiveFrom = "2.0.0";
        active.expiresAfterVersion = "3.0.0";
        active.baseVersion = 1L;
        comparisonService.decide(first.id, active);

        DiffResult afterActive = comparisonService.get(first.id);
        boolean annotated = afterActive.types.stream()
                .filter(e -> e.stableId.equals("intlib.Cfg"))
                .flatMap(e -> e.changes.stream())
                .anyMatch(c -> "linux@2.0.0".equals(c.exceptionRef));
        assertTrue(annotated, "ACTIVE platform exception annotates its change");
    }

    @Test
    void concurrentDecisionWithStaleBaseReturnsSymbolConflict() {
        String oldHash = ingest("3.0.0", "linux", "aarch64", false, true);
        String newHash = ingest("4.0.0", "linux", "aarch64", true, false);
        DiffResult cmp = comparisonService.compare(oldHash, newHash, "linux-aarch64-public");

        Decision first = decision("intlib.fn", "*", "ACCEPT", 0L, "3.0.0", "4.5.0");
        comparisonService.decide(cmp.id, first);

        Decision stale = decision("intlib.fn", "*", "REJECT", 0L, "4.0.0", "5.0.0");
        DecisionConflictException ex = assertThrows(DecisionConflictException.class,
                () -> comparisonService.decide(cmp.id, stale));
        assertEquals(1L, ex.getCurrentVersion());
        assertEquals(1, ex.getConflicts().size());
        assertEquals("intlib.fn", ex.getConflicts().get(0).get("stable_id"));

        // a different platform keeps its own conclusion: linux accepted here,
        // windows row coexists
        Decision windows = decision("intlib.fn", "windows", "REJECT", 1L, "4.0.0", null);
        assertDoesNotThrow(() -> comparisonService.decide(cmp.id, windows));

        DiffResult finalView = comparisonService.get(cmp.id);
        List<String> platforms = finalView.symbols.stream()
                .filter(e -> e.stableId.equals("intlib.fn"))
                .flatMap(e -> e.decisions.stream())
                .map(d -> d.platform).toList();
        assertTrue(platforms.contains("*"));
        assertTrue(platforms.contains("windows"));
    }

    private Decision decision(String stableId, String platform, String verdict,
                              long base, String from, String expires) {
        Decision d = new Decision();
        d.stableId = stableId;
        d.changeKind = "VARIADIC_CHANGED";
        d.platform = platform;
        d.verdict = verdict;
        d.rationale = "integration";
        d.effectiveFrom = from;
        d.expiresAfterVersion = expires;
        d.baseVersion = base;
        return d;
    }
}
