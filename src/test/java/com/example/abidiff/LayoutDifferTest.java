package com.example.abidiff;

import com.example.abidiff.diff.LayoutDiffer;
import com.example.abidiff.json.Docs.FieldDoc;
import com.example.abidiff.json.Docs.RecordDetail;
import com.example.abidiff.model.StoredTypes.SymbolRow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LayoutDifferTest {

    private static SymbolRow record(String stableId, long size, long align, RecordDetail detail) {
        return new SymbolRow("c", stableId, stableId, "TYPE_RECORD", "PUBLIC", "DEFAULT",
                "GLOBAL", null, false, size, align, false, detail, null, null);
    }

    private static FieldDoc f(String id, String name, String type, long off, long size, long align) {
        return new FieldDoc(id, name, type, off, size, align, false, null, false, null, null, false);
    }

    private static FieldDoc reserved(String id, String slot, long off) {
        return new FieldDoc(id, slot, "uint32_t", off, 4L, 4L, true, slot, false, null, null, false);
    }

    @Test
    void reusingReservedSlotIsNotMiddleInsertion() {
        var before = record("wgt_config", 32, 8, new RecordDetail("STRUCT", "wgt_config", List.of(
                f("flags", "flags", "uint32_t", 0, 4, 4),
                reserved("_reserved1", "_reserved1", 4),
                f("limit", "limit", "uint64_t", 8, 8, 8)), false, 0L));
        var after = record("wgt_config", 32, 8, new RecordDetail("STRUCT", "wgt_config", List.of(
                f("flags", "flags", "uint32_t", 0, 4, 4),
                f("_reserved1", "version", "uint32_t", 4, 4, 4),
                f("limit", "limit", "uint64_t", 8, 8, 8)), false, 0L));

        List<String> kinds = LayoutDiffer.compare(before, after).stream().map(LayoutDiffer.RawChange::kind).toList();

        assertThat(kinds).contains("LAYOUT_RESERVED_REUSED");
        assertThat(kinds).doesNotContain("LAYOUT_FIELD_INSERTED", "LAYOUT_FIELD_OFFSET");
    }

    @Test
    void insertingFieldInTheMiddleShiftsOffsets() {
        var before = record("p", 24, 8, new RecordDetail("STRUCT", "p", List.of(
                f("id", "id", "uint64_t", 0, 8, 8),
                f("len", "len", "uint32_t", 8, 4, 4),
                f("payload", "payload", "void*", 16, 8, 8)), false, 0L));
        var after = record("p", 32, 8, new RecordDetail("STRUCT", "p", List.of(
                f("id", "id", "uint64_t", 0, 8, 8),
                f("kind", "kind", "uint32_t", 8, 4, 4),
                f("len", "len", "uint32_t", 12, 4, 4),
                f("payload", "payload", "void*", 16, 8, 8),
                new FieldDoc("crc", "crc", "uint32_t", 24L, 4L, 4L, false, null, false, null, null, true)),
                false, 0L));

        var changes = LayoutDiffer.compare(before, after);
        var kinds = changes.stream().map(LayoutDiffer.RawChange::kind).toList();

        assertThat(kinds).contains("LAYOUT_FIELD_INSERTED", "LAYOUT_FIELD_OFFSET",
                "LAYOUT_TAIL_PRIVATE_ADDED");
        // private tail field grows sizeof but is tagged distinctly
        assertThat(changes).anyMatch(c -> c.kind().equals("LAYOUT_TAIL_PRIVATE_ADDED"));
    }

    @Test
    void tailPaddingElisionIsItsOwnKind() {
        var before = record("v", 16, 8, new RecordDetail("STRUCT", "v", List.of(
                f("data", "data", "void*", 0, 8, 8)), true, 8L));
        var after = record("v", 16, 8, new RecordDetail("STRUCT", "v", List.of(
                f("data", "data", "void*", 0, 8, 8)), false, 0L));

        var kinds = LayoutDiffer.compare(before, after).stream().map(LayoutDiffer.RawChange::kind).toList();
        assertThat(kinds).contains("LAYOUT_TAIL_PADDING_ELIDED");
    }

    @Test
    void bitfieldWidthChangeIsExplicitBitfieldChange() {
        var before = record("fl", 4, 4, new RecordDetail("STRUCT", "fl", List.of(
                new FieldDoc("mode", "mode", "unsigned", 0L, 4L, 4L, false, null, true, 1L, 3L, false)),
                false, 0L));
        var after = record("fl", 4, 4, new RecordDetail("STRUCT", "fl", List.of(
                new FieldDoc("mode", "mode", "unsigned", 0L, 4L, 4L, false, null, true, 1L, 5L, false)),
                false, 0L));

        var kinds = LayoutDiffer.compare(before, after).stream().map(LayoutDiffer.RawChange::kind).toList();
        assertThat(kinds).contains("BITFIELD_LAYOUT_CHANGED");
    }

    @Test
    void alignmentChangeIsReportedSeparatelyFromSize() {
        var before = record("box", 8, 4, new RecordDetail("STRUCT", "box", List.of(
                f("w", "w", "int32_t", 0, 4, 4), f("h", "h", "int32_t", 4, 4, 4)), false, 0L));
        var after = record("box", 16, 8, new RecordDetail("STRUCT", "box", List.of(
                f("w", "w", "int64_t", 0, 8, 8), f("h", "h", "int64_t", 8, 8, 8)), false, 0L));

        var kinds = LayoutDiffer.compare(before, after).stream().map(LayoutDiffer.RawChange::kind).toList();
        assertThat(kinds).contains("LAYOUT_ALIGN_CHANGED", "LAYOUT_SIZE_CHANGED",
                "LAYOUT_FIELD_TYPE", "LAYOUT_FIELD_OFFSET");
    }
}
