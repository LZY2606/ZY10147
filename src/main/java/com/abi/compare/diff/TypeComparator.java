package com.abi.compare.diff;

import com.abi.compare.model.AbiType;
import com.abi.compare.model.Field;
import com.abi.compare.rule.RuleSet;
import com.abi.compare.rule.Severity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Struct/union layout comparison.
 *
 * <p>Layout distinctions are kept explicit:
 * <ul>
 *   <li>tail append of a private field grows sizeof but never shifts members;</li>
 *   <li>reusing reserved padding fills previously unused bytes without shifts;</li>
 *   <li>inserting in the middle shifts later members and is a different event.</li>
 * </ul>
 */
public final class TypeComparator {
    private TypeComparator() {
    }

    public static List<Change> compare(AbiType oldT, AbiType newT, RuleSet rules) {
        List<Change> changes = new ArrayList<>();

        if (oldT.size != newT.size) {
            add(changes, rules, ChangeKind.TYPE_SIZE_CHANGED,
                    "sizeof changed (abi footprint of the type)",
                    String.valueOf(oldT.size), String.valueOf(newT.size));
        }
        if (oldT.alignment != newT.alignment) {
            add(changes, rules, ChangeKind.TYPE_ALIGN_CHANGED,
                    "alignment requirement changed",
                    String.valueOf(oldT.alignment), String.valueOf(newT.alignment));
        }
        long oldPad = tailPadding(oldT);
        long newPad = tailPadding(newT);
        if (oldPad != newPad) {
            add(changes, rules, ChangeKind.TAIL_PADDING_CHANGED,
                    "tail padding changed",
                    String.valueOf(oldPad), String.valueOf(newPad));
        }
        if (!Objects.equals(zeroSized(oldT), zeroSized(newT))) {
            add(changes, rules, ChangeKind.ZERO_SIZE_CHANGED,
                    "zero-sized-type state changed (explicit)",
                    String.valueOf(zeroSized(oldT)), String.valueOf(zeroSized(newT)));
        }

        Map<String, Field> oldByName = index(oldT);
        Map<String, Field> newByName = index(newT);

        List<String> shiftedSurvivors = new ArrayList<>();
        for (Map.Entry<String, Field> e : oldByName.entrySet()) {
            Field oldF = e.getValue();
            Field newF = newByName.get(e.getKey());
            if (newF == null) {
                add(changes, rules, ChangeKind.FIELD_REMOVED,
                        "field removed", describe(oldF), null, oldF.name, null);
                continue;
            }
            if (oldF.offset != newF.offset) {
                shiftedSurvivors.add(newF.name);
                add(changes, rules, ChangeKind.FIELD_OFFSET_CHANGED,
                        "field offset moved",
                        "@" + oldF.offset, "@" + newF.offset, newF.name, null);
            }
            if (!Objects.equals(str(oldF.type), str(newF.type))) {
                add(changes, rules, ChangeKind.FIELD_TYPE_CHANGED, "field type changed",
                        str(oldF.type), str(newF.type), newF.name, null);
            }
            if (!Objects.equals(oldF.bitOffset, newF.bitOffset)
                    || !Objects.equals(oldF.bitWidth, newF.bitWidth)) {
                add(changes, rules, ChangeKind.FIELD_BITFIELD_CHANGED,
                        "bitfield layout changed (explicit state)",
                        bit(oldF), bit(newF), newF.name, null);
            }
        }

        boolean[] occupied = occupancyBitmap(oldT);
        for (Map.Entry<String, Field> e : newByName.entrySet()) {
            if (oldByName.containsKey(e.getKey())) {
                continue;
            }
            Field newF = e.getValue();
            classifyInsertion(newF, oldT, occupied, rules, changes);
        }
        return changes;
    }

    /**
     * Byte occupancy of real (non-reserved-marker) fields in the old layout.
     * A new field landing entirely on unoccupied bytes inside the old sizeof
     * reuses reserved/interior padding even if a padding marker shifts aside.
     */
    private static boolean[] occupancyBitmap(AbiType type) {
        boolean[] occupied = new boolean[(int) Math.max(0, type.size)];
        if (type.fields == null) {
            return occupied;
        }
        for (Field f : type.fields) {
            if (Boolean.TRUE.equals(f.reservedPadding)) {
                continue;
            }
            for (long off = f.offset; off < f.offset + f.size && off < occupied.length; off++) {
                if (off >= 0) {
                    occupied[(int) off] = true;
                }
            }
        }
        return occupied;
    }

    private static void classifyInsertion(Field newF, AbiType oldT, boolean[] occupied,
                                          RuleSet rules, List<Change> changes) {
        if (newF.offset >= oldT.size) {
            boolean privateTail = Boolean.TRUE.equals(newF.privateField);
            String kind = privateTail
                    ? ChangeKind.FIELD_APPENDED_PRIVATE_TAIL
                    : ChangeKind.FIELD_APPENDED_PUBLIC;
            add(changes, rules, kind,
                    privateTail
                            ? "private field appended in the tail: sizeof grows, offsets stay put"
                            : "public field appended: sizeof of the public type grows",
                    null, describe(newF), newF.name, null);
            return;
        }
        boolean reusesPadding = true;
        for (long off = newF.offset; off < newF.offset + newF.size; off++) {
            if (off < 0 || off >= occupied.length || occupied[(int) off]) {
                reusesPadding = false;
                break;
            }
        }
        if (reusesPadding) {
            add(changes, rules, ChangeKind.PADDING_REUSED,
                    "new field consumes previously reserved/unused padding; no real member is displaced",
                    null, describe(newF), newF.name,
                    "offset " + newF.offset + " was unoccupied inside old size " + oldT.size);
        } else {
            add(changes, rules, ChangeKind.FIELD_INSERTED_MIDDLE,
                    "field inserted in the middle and occupies bytes that held a real member",
                    null, describe(newF), newF.name, null);
        }
    }

    private static Map<String, Field> index(AbiType type) {
        Map<String, Field> map = new LinkedHashMap<>();
        if (type.fields != null) {
            for (Field f : type.fields) {
                map.put(f.name, f);
            }
        }
        return map;
    }

    private static long tailPadding(AbiType type) {
        if (type.tailPadding != null) {
            return type.tailPadding;
        }
        if (type.fields == null || type.fields.isEmpty()) {
            return 0;
        }
        long end = 0;
        for (Field f : type.fields) {
            end = Math.max(end, f.offset + f.size);
        }
        return Math.max(0, type.size - end);
    }

    private static boolean zeroSized(AbiType type) {
        if (type.zeroSized != null) {
            return type.zeroSized;
        }
        return type.size == 0;
    }

    private static String describe(Field f) {
        return str(f.type) + " " + f.name + " @" + f.offset
                + (f.bitWidth != null ? " [bits " + f.bitOffset + "+" + f.bitWidth + "]" : "");
    }

    private static String bit(Field f) {
        if (f.bitOffset == null && f.bitWidth == null) {
            return "non-bitfield";
        }
        return "bitOffset=" + f.bitOffset + ",bitWidth=" + f.bitWidth;
    }


    private static Change add(List<Change> changes, RuleSet rules, String kind, String detail,
                              String from, String to) {
        return add(changes, rules, kind, detail, from, to, null, null);
    }

    private static Change add(List<Change> changes, RuleSet rules, String kind, String detail,
                              String from, String to, String member, String evidence) {
        Severity severity = rules.severityFor(kind, Severity.WARNING);
        Change change = new Change(kind, severity.name(), detail);
        change.from = from;
        change.to = to;
        change.member = member;
        change.evidence = evidence;
        changes.add(change);
        return change;
    }

    private static String str(String s) {
        return s == null ? "" : s;
    }
}
