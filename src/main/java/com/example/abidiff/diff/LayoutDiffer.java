package com.example.abidiff.diff;

import com.example.abidiff.json.Docs.FieldDoc;
import com.example.abidiff.json.Docs.RecordDetail;
import com.example.abidiff.model.StoredTypes.SymbolRow;

import java.util.*;

/**
 * Struct/union layout comparison.
 *
 * <p>Semantic boundaries required by the domain:
 * <ul>
 *   <li>appending a <b>private tail field</b> changes sizeof but is reported
 *       distinctly (rules may mark it compatible);</li>
 *   <li><b>reusing an explicit reserved/padding slot</b> (same stable slot id,
 *       reserved flag cleared) is distinct from inserting a new field in the
 *       middle;</li>
 *   <li>inserting a field in the middle shifts offsets and is breaking;</li>
 *   <li>tail padding elision (sizeof shrinks without a field change) is its
 *       own kind;</li>
 *   <li>bitfields carry an explicit state and never silently fall back to a
 *       byte-level comparison;</li>
 *   <li>zero-sized types are handled elsewhere by the caller.</li>
 * </ul>
 */
public final class LayoutDiffer {

    public record RawChange(String kind, String title, Map<String, Object> detail) {
    }

    private LayoutDiffer() {
    }

    public static List<RawChange> compare(SymbolRow a, SymbolRow b) {
        List<RawChange> out = new ArrayList<>();
        RecordDetail ra = a.record();
        RecordDetail rb = b.record();
        if (ra == null || rb == null) {
            return out;
        }

        boolean struct = !"UNION".equals(ra.record_kind()) && !"UNION".equals(rb.record_kind());
        if (struct) {
            compareStruct(a, b, ra, rb, out);
        } else {
            compareUnion(a, b, ra, rb, out);
        }

        if (!Objects.equals(ra.tail_padding(), rb.tail_padding())
                || !Objects.equals(ra.tail_padding_bytes(), rb.tail_padding_bytes())) {
            Long pa = ra.tail_padding_bytes();
            Long pb = rb.tail_padding_bytes();
            boolean elided = (pb == null || pb == 0) && pa != null && pa > 0;
            String kind = elided ? "LAYOUT_TAIL_PADDING_ELIDED" : "LAYOUT_SIZE_CHANGED";
            out.add(new RawChange(kind,
                    "tail padding changed (" + pa + " -> " + pb + " bytes)",
                    map("tailPaddingBefore", pa, "tailPaddingAfter", pb)));
        }
        return out;
    }

    private static void compareStruct(SymbolRow a, SymbolRow b, RecordDetail ra, RecordDetail rb,
                                      List<RawChange> out) {
        LinkedHashMap<String, FieldDoc> oldMap = indexFields(ra);
        LinkedHashMap<String, FieldDoc> newMap = indexFields(rb);

        List<FieldDoc> oldOrdered = ra.fields() == null ? List.of() : ra.fields();
        List<FieldDoc> newOrdered = rb.fields() == null ? List.of() : rb.fields();

        // 1) reserved slot reuse: a slot that was explicitly reserved before and
        //    now carries a real field (same stable slot id).
        for (FieldDoc nf : newOrdered) {
            FieldDoc of = nf.stable_id() == null ? null : oldMap.get(nf.stable_id());
            if (of != null && Boolean.TRUE.equals(of.reservedFlag()) && !Boolean.TRUE.equals(nf.reservedFlag())) {
                out.add(new RawChange("LAYOUT_RESERVED_REUSED",
                        "reserved slot " + of.reserved_id() + " reused by field " + nf.name(),
                        map("slot", of.reserved_id() != null ? of.reserved_id() : of.stable_id(),
                                "offset", nf.offset(), "type", nf.type())));
            }
        }

        // 2) removed / newly appeared fields
        for (FieldDoc of : oldOrdered) {
            if (!newMap.containsKey(of.stable_id())) {
                out.add(new RawChange("LAYOUT_FIELD_REMOVED",
                        "field removed: " + of.name(),
                        map("field", of.name(), "offset", of.offset(), "bitfield",
                                Boolean.TRUE.equals(of.bitfieldFlag()))));
            }
        }
        for (FieldDoc nf : newOrdered) {
            if (!oldMap.containsKey(nf.stable_id()) && !reusesOldReserved(nf, oldMap)) {
                boolean inserted = insertedInMiddle(nf, of -> oldMap.containsKey(of.stable_id()), oldOrdered, newOrdered);
                String kind = inserted ? "LAYOUT_FIELD_INSERTED"
                        : (Boolean.TRUE.equals(nf.tailFlag()) ? "LAYOUT_TAIL_PRIVATE_ADDED"
                        : "LAYOUT_FIELD_INSERTED");
                String title = Boolean.TRUE.equals(nf.tailFlag())
                        ? "private tail field appended: " + nf.name()
                        : (inserted ? "field inserted in the middle: " + nf.name()
                        : "field appended: " + nf.name());
                out.add(new RawChange(kind, title,
                        map("field", nf.name(), "offset", nf.offset(), "tail", Boolean.TRUE.equals(nf.tailFlag()),
                                "bitfield", Boolean.TRUE.equals(nf.bitfieldFlag()))));
            }
        }

        // 3) common fields: offset / type / bitfield changes
        for (FieldDoc nf : newOrdered) {
            FieldDoc of = oldMap.get(nf.stable_id());
            if (of == null) {
                continue;
            }
            boolean wasReserved = Boolean.TRUE.equals(of.reservedFlag());
            boolean nowReserved = Boolean.TRUE.equals(nf.reservedFlag());
            if (wasReserved || nowReserved) {
                continue; // reserved-slot reuse already emitted
            }
            if (!Objects.equals(of.offset(), nf.offset())) {
                out.add(new RawChange("LAYOUT_FIELD_OFFSET",
                        "field " + nf.name() + " offset " + of.offset() + " -> " + nf.offset(),
                        map("field", nf.name(), "before", of.offset(), "after", nf.offset())));
            }
            if (!Objects.equals(of.type(), nf.type()) || !Objects.equals(of.size(), nf.size())) {
                out.add(new RawChange("LAYOUT_FIELD_TYPE",
                        "field " + nf.name() + " type " + of.type() + " -> " + nf.type(),
                        map("field", nf.name(), "before", of.type(), "after", nf.type())));
            }
            if (bitfieldStateChanged(of, nf)) {
                out.add(new RawChange("BITFIELD_LAYOUT_CHANGED",
                        "bitfield layout changed for " + nf.name(),
                        map("field", nf.name(),
                                "beforeWidth", of.bitfield_width_bits(), "afterWidth", nf.bitfield_width_bits(),
                                "beforeOffsetBits", of.bitfield_offset_bits(),
                                "afterOffsetBits", nf.bitfield_offset_bits())));
            }
        }

        if (!Objects.equals(a.size(), b.size())) {
            boolean onlyTailPrivate = onlyTailPrivateGrowth(out);
            String kind = onlyTailPrivate ? "LAYOUT_TAIL_PRIVATE_ADDED" : "LAYOUT_SIZE_CHANGED";
            out.add(new RawChange(kind,
                    "sizeof changed " + a.size() + " -> " + b.size(),
                    map("sizeBefore", a.size(), "sizeAfter", b.size(),
                            "onlyTailPrivateGrowth", onlyTailPrivate)));
        }
        if (!Objects.equals(a.align(), b.align())) {
            out.add(new RawChange("LAYOUT_ALIGN_CHANGED",
                    "alignment changed " + a.align() + " -> " + b.align(),
                    map("alignBefore", a.align(), "alignAfter", b.align())));
        }
    }

    private static void compareUnion(SymbolRow a, SymbolRow b, RecordDetail ra, RecordDetail rb,
                                     List<RawChange> out) {
        // Union members all start at offset 0; only membership/size matters.
        Set<String> old = new HashSet<>(indexFields(ra).keySet());
        Set<String> now = new HashSet<>(indexFields(rb).keySet());
        for (String removed : old) {
            if (!now.contains(removed)) {
                out.add(new RawChange("LAYOUT_FIELD_REMOVED", "union member removed: " + removed,
                        map("field", removed)));
            }
        }
        for (String added : now) {
            if (!old.contains(added)) {
                out.add(new RawChange("LAYOUT_FIELD_INSERTED", "union member added: " + added,
                        map("field", added)));
            }
        }
        if (!Objects.equals(a.size(), b.size())) {
            out.add(new RawChange("LAYOUT_SIZE_CHANGED", "union size changed " + a.size() + " -> " + b.size(),
                    map("sizeBefore", a.size(), "sizeAfter", b.size())));
        }
        if (!Objects.equals(a.align(), b.align())) {
            out.add(new RawChange("LAYOUT_ALIGN_CHANGED", "union alignment changed " + a.align() + " -> " + b.align(),
                    map("alignBefore", a.align(), "alignAfter", b.align())));
        }
    }

    private static boolean reusesOldReserved(FieldDoc nf, Map<String, FieldDoc> oldMap) {
        FieldDoc of = oldMap.get(nf.stable_id());
        return of != null && Boolean.TRUE.equals(of.reservedFlag()) && !Boolean.TRUE.equals(nf.reservedFlag());
    }

    private static boolean insertedInMiddle(FieldDoc nf,
                                            java.util.function.Predicate<FieldDoc> existsInOld,
                                            List<FieldDoc> oldOrdered, List<FieldDoc> newOrdered) {
        if (Boolean.TRUE.equals(nf.tailFlag())) {
            return false;
        }
        int idx = indexOf(newOrdered, nf.stable_id());
        for (int i = idx + 1; i < newOrdered.size(); i++) {
            FieldDoc later = newOrdered.get(i);
            if (Boolean.TRUE.equals(later.tailFlag())) {
                continue;
            }
            if (oldMapContains(oldOrdered, later.stable_id())) {
                return true; // a pre-existing field now sits after the new one -> middle insertion
            }
        }
        return false;
    }

    private static boolean oldMapContains(List<FieldDoc> old, String stableId) {
        for (FieldDoc f : old) {
            if (Objects.equals(f.stable_id(), stableId)) {
                return true;
            }
        }
        return false;
    }

    private static int indexOf(List<FieldDoc> fields, String stableId) {
        for (int i = 0; i < fields.size(); i++) {
            if (Objects.equals(fields.get(i).stable_id(), stableId)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean bitfieldStateChanged(FieldDoc a, FieldDoc b) {
        boolean ba = Boolean.TRUE.equals(a.bitfieldFlag());
        boolean bb = Boolean.TRUE.equals(b.bitfieldFlag());
        if (ba != bb) {
            return true;
        }
        return ba && (!Objects.equals(a.bitfield_width_bits(), b.bitfield_width_bits())
                || !Objects.equals(a.bitfield_offset_bits(), b.bitfield_offset_bits()));
    }

    private static boolean onlyTailPrivateGrowth(List<RawChange> changes) {
        boolean hasTail = false;
        for (RawChange c : changes) {
            switch (c.kind()) {
                case "LAYOUT_TAIL_PRIVATE_ADDED" -> hasTail = true;
                case "LAYOUT_FIELD_INSERTED", "LAYOUT_FIELD_REMOVED", "LAYOUT_FIELD_OFFSET",
                        "LAYOUT_FIELD_TYPE", "LAYOUT_TAIL_PADDING_ELIDED",
                        "BITFIELD_LAYOUT_CHANGED", "LAYOUT_RESERVED_REUSED" -> {
                    return false;
                }
                default -> {
                }
            }
        }
        return hasTail;
    }

    private static LinkedHashMap<String, FieldDoc> indexFields(RecordDetail r) {
        LinkedHashMap<String, FieldDoc> m = new LinkedHashMap<>();
        if (r.fields() != null) {
            for (FieldDoc f : r.fields()) {
                m.put(f.stable_id(), f);
            }
        }
        return m;
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }
}
