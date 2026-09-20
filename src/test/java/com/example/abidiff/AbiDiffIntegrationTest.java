package com.example.abidiff;

import com.example.abidiff.decision.DecisionService;
import com.example.abidiff.diff.ComparisonRepository;
import com.example.abidiff.diff.ComparisonResult;
import com.example.abidiff.diff.Finding;
import com.example.abidiff.model.Enums.Severity;
import com.example.abidiff.model.StoredTypes.LoadedSnapshot;
import com.example.abidiff.model.StoredTypes.SnapshotRow;
import com.example.abidiff.snapshot.SnapshotRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class AbiDiffIntegrationTest {

    @Autowired SnapshotRepository snapshots;
    @Autowired ComparisonRepository comparisons;
    @Autowired DecisionService decisions;

    private String fixture(String name) throws Exception {
        return StreamUtils.copyToString(new ClassPathResource("fixtures/" + name).getInputStream(),
                StandardCharsets.UTF_8);
    }

    private String[] linuxSnapshots() throws Exception {
        String l = snapshots.importSnapshot("libwidget", "1.0.0",
                fixture("linux-x86_64-1.0.0.json")).id();
        String r = snapshots.importSnapshot("libwidget", "1.1.0",
                fixture("linux-x86_64-1.1.0.json")).id();
        return new String[]{l, r};
    }

    @Test
    void snapshotsAreContentHashDeduplicated() throws Exception {
        SnapshotRow first = snapshots.importSnapshot("dedup-dist", "1.0.0",
                fixture("windows-x86_64-1.0.0.json"));
        SnapshotRow second = snapshots.importSnapshot("dedup-dist", "1.0.0",
                fixture("windows-x86_64-1.0.0.json"));
        assertThat(second.id()).isEqualTo(first.id());
        // whitespace/formatting change must still canonicalise to the same hash
        String reformatted = fixture("windows-x86_64-1.0.0.json").replace(": ", ":  ");
        SnapshotRow third = snapshots.importSnapshot("dedup-dist", "1.0.0", reformatted);
        assertThat(third.contentHash()).isEqualTo(first.contentHash());
    }

    @Test
    void comparisonIsAtomicIdempotentAndRuleRevisionCoexists() throws Exception {
        var ids = linuxSnapshots();
        ComparisonResult rev1 = comparisons.compare(ids[0], ids[1], "rs-linux-x86_64-1");
        ComparisonResult rev2 = comparisons.compare(ids[0], ids[1], "rs-linux-x86_64-2");

        assertThat(rev1.id()).isNotEqualTo(rev2.id());
        // repeat call returns the same comparison (idempotent)
        assertThat(comparisons.compare(ids[0], ids[1], "rs-linux-x86_64-2").id()).isEqualTo(rev2.id());

        Severity reservedRev1 = severity(rev1, "LAYOUT_RESERVED_REUSED");
        Severity reservedRev2 = severity(rev2, "LAYOUT_RESERVED_REUSED");
        assertThat(reservedRev1).isEqualTo(Severity.BREAKING);
        assertThat(reservedRev2).isEqualTo(Severity.COMPATIBLE);
    }

    @Test
    void detectsCallingConventionVarargsZstBitfieldAndTailPadding() throws Exception {
        var ids = linuxSnapshots();
        ComparisonResult c = comparisons.compare(ids[0], ids[1], "rs-linux-x86_64-2");
        var kinds = c.findings().stream().map(Finding::kind).toList();

        assertThat(kinds).contains("CC_VARARGS_TOGGLED", "ZST_TO_SIZED",
                "BITFIELD_LAYOUT_CHANGED", "LAYOUT_TAIL_PADDING_ELIDED",
                "LAYOUT_RESERVED_REUSED", "LAYOUT_FIELD_INSERTED", "SYMBOL_RENAMED");
    }

    @Test
    void renameRequiresExplicitEvidenceNoEditDistance() throws Exception {
        // windows fixture has no alias between old/new symbol names: stable_id
        // mismatch must surface as add+remove, never a fuzzy rename.
        String l = snapshots.importSnapshot("w", "1.0.0", fixture("windows-x86_64-1.0.0.json")).id();
        String r = snapshots.importSnapshot("w", "1.1.0", fixture("windows-x86_64-1.1.0.json")).id();
        ComparisonResult c = comparisons.compare(l, r, "rs-windows-x86_64-1");
        long renames = c.findings().stream().filter(f -> f.kind().equals("SYMBOL_RENAMED")).count();
        assertThat(renames).isZero();

        // macos carries an explicit WEAK_ALIAS -> rename is accepted evidence
        String ml = snapshots.importSnapshot("m", "1.0.0", fixture("macos-aarch64-1.0.0.json")).id();
        String mr = snapshots.importSnapshot("m", "1.1.0", fixture("macos-aarch64-1.1.0.json")).id();
        ComparisonResult mc = comparisons.compare(ml, mr, "rs-macos-aarch64-1");
        assertThat(mc.findings()).anyMatch(f -> f.kind().equals("SYMBOL_RENAMED"));
    }

    @Test
    void walksReferenceGraphToPublicEntryPoints() throws Exception {
        var ids = linuxSnapshots();
        ComparisonResult c = comparisons.compare(ids[0], ids[1], "rs-linux-x86_64-2");
        Finding reserved = c.findings().stream()
                .filter(f -> f.kind().equals("LAYOUT_RESERVED_REUSED")).findFirst().orElseThrow();
        // wgt_config is used via wgt_internal_compute <- wgt_compute and by wgt_config_init
        assertThat(reserved.affectedRoots()).contains("wgt_compute", "wgt_config_init");
    }

    @Test
    void expiredExceptionDoesNotSuppressBreakingAndRulesetBinds() throws Exception {
        var ids = linuxSnapshots();
        ComparisonResult c = comparisons.compare(ids[0], ids[1], "rs-linux-x86_64-2");

        decisions.submit(new DecisionService.SubmitRequest(
                c.id(), "wgt_log", "linux", com.example.abidiff.model.Enums.DecisionKind.EXCEPTION,
                "only for 1.0.x", "fixture",
                "1.0.0", "1.0.255", "1.0.255", c.rulesetId(), decisions.rev(c.id())));

        // evaluated at right version 1.1.0 -> exception out of scope
        var applicable = decisions.applicable(c.id(), "linux", "1.1.0", c.rulesetId());
        assertThat(applicable).isEmpty();
        // at 1.0.x it would apply
        assertThat(decisions.applicable(c.id(), "linux", "1.0.5", c.rulesetId())).hasSize(1);

        // a decision bound to a different ruleset revision never applies
        assertThat(decisions.applicable(c.id(), "linux", "1.0.5", "rs-linux-x86_64-1")).isEmpty();
    }

    @Test
    void concurrentDecisionReturnsSymbolLevelConflict() throws Exception {
        var ids = linuxSnapshots();
        ComparisonResult c = comparisons.compare(ids[0], ids[1], "rs-linux-x86_64-2");
        decisions.submit(new DecisionService.SubmitRequest(
                c.id(), "wgt_compute", "linux", com.example.abidiff.model.Enums.DecisionKind.EXCEPTION,
                "first", "alice", "1.1.0", "1.1.255", "1.1.255", c.rulesetId(), 0));

        // second submitter still uses base revision 0 -> 409 with the conflicting symbol
        assertThatThrownBy(() -> decisions.submit(new DecisionService.SubmitRequest(
                c.id(), "wgt_compute", "linux", com.example.abidiff.model.Enums.DecisionKind.ACCEPT_BREAK,
                "stale base", "bob", null, null, null, c.rulesetId(), 0)))
                .isInstanceOf(DecisionService.ConflictException.class)
                .satisfies(e -> assertThat(((DecisionService.ConflictException) e).conflicts)
                        .extracting(x -> x.symbolStableId()).contains("wgt_compute"));
    }

    @Test
    void differentPlatformsKeepDifferentConclusions() throws Exception {
        var ids = linuxSnapshots();
        ComparisonResult c = comparisons.compare(ids[0], ids[1], "rs-linux-x86_64-2");
        decisions.submit(new DecisionService.SubmitRequest(
                c.id(), "wgt_compute", "linux", com.example.abidiff.model.Enums.DecisionKind.EXCEPTION,
                "linux-only", "alice", "1.1.0", "1.1.255", "1.1.255", c.rulesetId(),
                decisions.rev(c.id())));
        // after linux decision revision bumped to 1, a windows decision on same symbol coexists
        decisions.submit(new DecisionService.SubmitRequest(
                c.id(), "wgt_compute", "windows", com.example.abidiff.model.Enums.DecisionKind.REJECT_RELEASE,
                "windows disagrees", "carol", null, null, null, c.rulesetId(),
                decisions.rev(c.id())));

        assertThat(decisions.applicable(c.id(), "linux", "1.1.0", c.rulesetId()))
                .singleElement().extracting(DecisionService.ResolutionView::platform).isEqualTo("linux");
        assertThat(decisions.applicable(c.id(), "windows", "1.1.0", c.rulesetId()))
                .singleElement().extracting(DecisionService.ResolutionView::decision).isEqualTo("REJECT_RELEASE");
    }

    @Test
    void rejectsComparisonAcrossPlatforms() throws Exception {
        String linux = snapshots.importSnapshot("cross", "1.0.0", fixture("linux-x86_64-1.0.0.json")).id();
        String windows = snapshots.importSnapshot("cross", "1.0.0",
                fixture("windows-x86_64-1.0.0.json")).id();
        assertThatThrownBy(() -> comparisons.compare(linux, windows, "rs-linux-x86_64-2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("share platform/arch");
    }

    private Severity severity(ComparisonResult c, String kind) {
        return c.findings().stream().filter(f -> f.kind().equals(kind)).findFirst()
                .orElseThrow().severity();
    }
}
