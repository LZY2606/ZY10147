package com.example.abidiff.rules;

import com.example.abidiff.model.Enums.Boundary;
import com.example.abidiff.model.Enums.Severity;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.example.abidiff.model.Enums.Severity.*;
import static com.example.abidiff.rules.ChangeKinds.*;

/**
 * Built-in platform/arch rule sets. These encode the judgement policy the task
 * calls out (tail padding reuse vs middle insertion, private tail fields,
 * varargs/calling convention, ZSTs, visibility, public vs private boundary).
 *
 * <p>linux/x86_64 has two revisions to demonstrate that the same pair of
 * snapshots produces coexisting, reproducible conclusions under different
 * rule sets; a rule revision never extends an exception approved against an
 * older revision.
 */
public final class DefaultRuleSets {
    private DefaultRuleSets() {
    }

    public static Map<String, RuleSet> all() {
        Map<String, RuleSet> out = new LinkedHashMap<>();
        out.put("rs-linux-x86_64-1",
                new RuleSet("rs-linux-x86_64-1", "linux", "x86_64", 1, false,
                        "Baseline SysV policy: any ABI change on public symbols is breaking.",
                        linuxBaseline()));
        out.put("rs-linux-x86_64-2",
                new RuleSet("rs-linux-x86_64-2", "linux", "x86_64", 2, true,
                        "Refined: reuse of documented reserved slots and private tail fields "
                                + "are compatible; middle insertion and tail-padding elision still break.",
                        linuxRefined()));
        out.put("rs-windows-x86_64-1",
                new RuleSet("rs-windows-x86_64-1", "windows", "x86_64", 1, true,
                        "Win64: calling convention and struct layout are part of the contract.",
                        windowsPolicy()));
        out.put("rs-macos-aarch64-1",
                new RuleSet("rs-macos-aarch64-1", "macos", "aarch64", 1, true,
                        "Darwin/arm64 PAC + AAPCS policy; aliases/weak symbols allowed.",
                        macosPolicy()));
        return out;
    }

    private static Map<Boundary, Map<String, Severity>> linuxBaseline() {
        Map<Boundary, Map<String, Severity>> m = blank();
        Map<String, Severity> pub = m.get(Boundary.PUBLIC);
        put(pub, SYMBOL_ADDED, COMPATIBLE);
        put(pub, SYMBOL_REMOVED, BREAKING);
        put(pub, SYMBOL_RENAMED, BREAKING);
        put(pub, VISIBILITY_REDUCED, BREAKING);
        put(pub, VISIBILITY_RAISED, COMPATIBLE);
        put(pub, CC_CHANGED, BREAKING);
        put(pub, CC_VARARGS_TOGGLED, BREAKING);
        put(pub, PARAM_TYPE_CHANGED, BREAKING);
        put(pub, PARAM_ADDED, BREAKING);
        put(pub, PARAM_REMOVED, BREAKING);
        put(pub, RETURN_TYPE_CHANGED, BREAKING);
        put(pub, VAR_TYPE_CHANGED, BREAKING);
        put(pub, ZST_TO_SIZED, BREAKING);
        put(pub, SIZED_TO_ZST, BREAKING);
        put(pub, LAYOUT_SIZE_CHANGED, BREAKING);
        put(pub, LAYOUT_ALIGN_CHANGED, BREAKING);
        put(pub, LAYOUT_FIELD_INSERTED, BREAKING);
        put(pub, LAYOUT_FIELD_REMOVED, BREAKING);
        put(pub, LAYOUT_FIELD_OFFSET, BREAKING);
        put(pub, LAYOUT_FIELD_TYPE, BREAKING);
        put(pub, LAYOUT_RESERVED_REUSED, BREAKING);
        put(pub, LAYOUT_TAIL_PRIVATE_ADDED, BREAKING);
        put(pub, LAYOUT_TAIL_PADDING_ELIDED, BREAKING);
        put(pub, BITFIELD_LAYOUT_CHANGED, BREAKING);
        return m;
    }

    private static Map<Boundary, Map<String, Severity>> linuxRefined() {
        Map<Boundary, Map<String, Severity>> m = linuxBaseline();
        Map<String, Severity> pub = m.get(Boundary.PUBLIC);
        put(pub, LAYOUT_RESERVED_REUSED, COMPATIBLE);
        put(pub, LAYOUT_TAIL_PRIVATE_ADDED, COMPATIBLE);
        put(pub, LAYOUT_SIZE_CHANGED, COMPATIBLE); // benign if only due to compatible tail growth
        return m;
    }

    private static Map<Boundary, Map<String, Severity>> windowsPolicy() {
        Map<Boundary, Map<String, Severity>> m = blank();
        Map<String, Severity> pub = m.get(Boundary.PUBLIC);
        put(pub, SYMBOL_ADDED, COMPATIBLE);
        put(pub, SYMBOL_REMOVED, BREAKING);
        put(pub, SYMBOL_RENAMED, BREAKING);
        put(pub, VISIBILITY_REDUCED, BREAKING);
        put(pub, VISIBILITY_RAISED, COMPATIBLE);
        put(pub, CC_CHANGED, BREAKING);
        put(pub, CC_VARARGS_TOGGLED, BREAKING);
        put(pub, PARAM_TYPE_CHANGED, BREAKING);
        put(pub, PARAM_ADDED, BREAKING);
        put(pub, PARAM_REMOVED, BREAKING);
        put(pub, RETURN_TYPE_CHANGED, BREAKING);
        put(pub, VAR_TYPE_CHANGED, BREAKING);
        put(pub, ZST_TO_SIZED, BREAKING);
        put(pub, SIZED_TO_ZST, BREAKING);
        put(pub, LAYOUT_SIZE_CHANGED, BREAKING);
        put(pub, LAYOUT_ALIGN_CHANGED, BREAKING);
        put(pub, LAYOUT_FIELD_INSERTED, BREAKING);
        put(pub, LAYOUT_FIELD_REMOVED, BREAKING);
        put(pub, LAYOUT_FIELD_OFFSET, BREAKING);
        put(pub, LAYOUT_FIELD_TYPE, BREAKING);
        put(pub, LAYOUT_RESERVED_REUSED, COMPATIBLE);
        put(pub, LAYOUT_TAIL_PRIVATE_ADDED, BREAKING);
        put(pub, LAYOUT_TAIL_PADDING_ELIDED, BREAKING);
        put(pub, BITFIELD_LAYOUT_CHANGED, BREAKING);
        return m;
    }

    private static Map<Boundary, Map<String, Severity>> macosPolicy() {
        Map<Boundary, Map<String, Severity>> m = blank();
        Map<String, Severity> pub = m.get(Boundary.PUBLIC);
        put(pub, SYMBOL_ADDED, COMPATIBLE);
        put(pub, SYMBOL_REMOVED, BREAKING);
        put(pub, SYMBOL_RENAMED, COMPATIBLE); // explicit alias rename is tolerated on darwin
        put(pub, VISIBILITY_REDUCED, BREAKING);
        put(pub, VISIBILITY_RAISED, INFO);
        put(pub, CC_CHANGED, BREAKING);
        put(pub, CC_VARARGS_TOGGLED, BREAKING);
        put(pub, PARAM_TYPE_CHANGED, BREAKING);
        put(pub, PARAM_ADDED, BREAKING);
        put(pub, PARAM_REMOVED, BREAKING);
        put(pub, RETURN_TYPE_CHANGED, BREAKING);
        put(pub, VAR_TYPE_CHANGED, BREAKING);
        put(pub, ZST_TO_SIZED, BREAKING);
        put(pub, SIZED_TO_ZST, BREAKING);
        put(pub, LAYOUT_SIZE_CHANGED, BREAKING);
        put(pub, LAYOUT_ALIGN_CHANGED, BREAKING);
        put(pub, LAYOUT_FIELD_INSERTED, BREAKING);
        put(pub, LAYOUT_FIELD_REMOVED, BREAKING);
        put(pub, LAYOUT_FIELD_OFFSET, BREAKING);
        put(pub, LAYOUT_FIELD_TYPE, BREAKING);
        put(pub, LAYOUT_RESERVED_REUSED, COMPATIBLE);
        put(pub, LAYOUT_TAIL_PRIVATE_ADDED, COMPATIBLE);
        put(pub, LAYOUT_TAIL_PADDING_ELIDED, BREAKING);
        put(pub, BITFIELD_LAYOUT_CHANGED, BREAKING);
        return m;
    }

    private static Map<Boundary, Map<String, Severity>> blank() {
        Map<Boundary, Map<String, Severity>> m = new LinkedHashMap<>();
        m.put(Boundary.PUBLIC, new LinkedHashMap<>());
        m.put(Boundary.PRIVATE, new LinkedHashMap<>());
        return m;
    }

    private static void put(Map<String, Severity> m, String k, Severity v) {
        m.put(k, v);
    }
}
