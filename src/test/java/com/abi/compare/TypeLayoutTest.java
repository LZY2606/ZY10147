package com.abi.compare;

import com.abi.compare.diff.Change;
import com.abi.compare.diff.TypeComparator;
import com.abi.compare.model.AbiType;
import com.abi.compare.model.Field;
import com.abi.compare.rule.RuleSet;
import com.abi.compare.rule.RuleRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class TypeLayoutTest {

    private final RuleSet rules = new RuleRegistry().all().get("linux-aarch64-public");

    private Map<String, Change> kinds(AbiType oldT, AbiType newT) {
        return TypeComparator.compare(oldT, newT, rules).stream()
                .collect(Collectors.toMap(c -> c.kind + (c.member == null ? "" : ":" + c.member),
                        Function.identity(), (a, b) -> a));
    }

    private Field field(String name, String type, long offset, long size) {
        Field f = new Field();
        f.name = name;
        f.type = type;
        f.offset = offset;
        f.size = size;
        return f;
    }

    @Test
    void middleInsertionIsDifferentFromPaddingReuse() {
        AbiType oldT = struct(16, 8, 0L,
                field("a", "u32", 0, 4), field("b", "u64", 8, 8));

        // new field at offset 4 inside 4-byte gap; survivors keep offsets => padding reuse
        AbiType reused = struct(16, 8, 2L,
                field("a", "u32", 0, 4), field("x", "u32", 4, 4),
                field("b", "u64", 8, 8));
        Map<String, Change> reusedChanges = kinds(oldT, reused);
        assertTrue(reusedChanges.containsKey("PADDING_REUSED:x"),
                "filling reserved padding is PADDING_REUSED");
        assertFalse(reusedChanges.containsKey("FIELD_OFFSET_CHANGED:b"),
                "padding reuse never shifts surviving fields");
        assertEquals("WARNING", reusedChanges.get("PADDING_REUSED:x").severity);

        // new field at 8 displaces b (its bytes used to hold b) => real middle insertion
        AbiType inserted = struct(24, 8, 0L,
                field("a", "u32", 0, 4), field("x", "u64", 8, 8),
                field("b", "u64", 16, 8));
        Map<String, Change> insertChanges = kinds(oldT, inserted);
        assertTrue(insertChanges.containsKey("FIELD_INSERTED_MIDDLE:x"));
        assertTrue(insertChanges.containsKey("FIELD_OFFSET_CHANGED:b"));
        assertEquals("BREAKING", insertChanges.get("FIELD_INSERTED_MIDDLE:x").severity);
    }

    @Test
    void privateTailAppendGrowsSizeWithoutShifting() {
        AbiType oldT = struct(16, 8, 0L,
                field("a", "u32", 0, 4), field("b", "u64", 8, 8));
        Field priv = field("ctx", "u64", 16, 8);
        priv.privateField = true;
        AbiType newT = struct(24, 8, 0L,
                field("a", "u32", 0, 4), field("b", "u64", 8, 8), priv);

        Map<String, Change> changes = kinds(oldT, newT);
        assertTrue(changes.containsKey("FIELD_APPENDED_PRIVATE_TAIL:ctx"));
        assertTrue(changes.containsKey("TYPE_SIZE_CHANGED"));
        assertFalse(changes.containsKey("FIELD_OFFSET_CHANGED:b"),
                "tail append must not shift earlier fields");
        assertEquals("WARNING", changes.get("FIELD_APPENDED_PRIVATE_TAIL:ctx").severity);
    }

    @Test
    void publicTailAppendIsBreaking() {
        AbiType oldT = struct(16, 8, 0L, field("a", "u32", 0, 4));
        AbiType newT = struct(24, 8, 0L,
                field("a", "u32", 0, 4), field("pub", "u64", 16, 8));
        assertEquals("BREAKING", kinds(oldT, newT).get("FIELD_APPENDED_PUBLIC:pub").severity);
    }

    @Test
    void tailPaddingAndAlignmentAreReportedSeparately() {
        AbiType oldT = struct(24, 8, 6L, field("a", "u64", 0, 8));
        AbiType newT = struct(32, 16, 8L, field("a", "u64", 0, 8));
        Map<String, Change> changes = kinds(oldT, newT);
        assertTrue(changes.containsKey("TYPE_ALIGN_CHANGED"));
        assertTrue(changes.containsKey("TYPE_SIZE_CHANGED"));
        assertTrue(changes.containsKey("TAIL_PADDING_CHANGED"));
    }

    @Test
    void bitfieldAndZeroSizedTypeRequireExplicitChanges() {
        AbiType oldT = struct(4, 4, 0L);
        Field f1 = field("flags", "u32", 0, 4);
        f1.bitOffset = 0;
        f1.bitWidth = 3;
        oldT.fields = List.of(f1);
        AbiType newT = struct(4, 4, 0L);
        Field f2 = field("flags", "u32", 0, 4);
        f2.bitOffset = 0;
        f2.bitWidth = 5;
        newT.fields = List.of(f2);
        assertEquals("BREAKING",
                kinds(oldT, newT).get("FIELD_BITFIELD_CHANGED:flags").severity);

        AbiType z0 = struct(0, 1, 0L);
        z0.zeroSized = true;
        AbiType z1 = struct(1, 1, 0L);
        z1.zeroSized = false;
        assertTrue(kinds(z0, z1).containsKey("ZERO_SIZE_CHANGED"));
    }

    private AbiType struct(long size, long alignment, Long tailPadding, Field... fields) {
        AbiType t = new AbiType();
        t.stableId = "T";
        t.kind = "STRUCT";
        t.name = "T";
        t.size = size;
        t.alignment = alignment;
        t.tailPadding = tailPadding;
        t.fields = List.of(fields);
        return t;
    }
}
