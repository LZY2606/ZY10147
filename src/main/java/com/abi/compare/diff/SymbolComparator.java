package com.abi.compare.diff;

import com.abi.compare.model.AbiSymbol;
import com.abi.compare.model.Parameter;
import com.abi.compare.rule.RuleSet;
import com.abi.compare.rule.Severity;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Produces symbol-level changes. Correspondence is stable-id based only. */
public final class SymbolComparator {
    private SymbolComparator() {
    }

    public static List<Change> compare(AbiSymbol oldS, AbiSymbol newS, RuleSet rules) {
        List<Change> changes = new ArrayList<>();

        if (!Objects.equals(oldS.name, newS.name)) {
            add(changes, rules, ChangeKind.SYMBOL_RENAMED,
                    "platform name changed; matched by explicit stable_id only",
                    oldS.name, newS.name, null,
                    "stable_id=" + oldS.stableId);
        }

        if (!Objects.equals(str(oldS.linkage), str(newS.linkage))) {
            add(changes, rules, ChangeKind.LINKAGE_CHANGED, "export/import linkage changed",
                    oldS.linkage, newS.linkage);
        }
        if (!Objects.equals(str(oldS.visibility), str(newS.visibility))) {
            add(changes, rules, ChangeKind.VISIBILITY_CHANGED, "symbol visibility changed",
                    oldS.visibility, newS.visibility);
        }
        if (!Objects.equals(str(oldS.callingConvention), str(newS.callingConvention))) {
            add(changes, rules, ChangeKind.CALLING_CONVENTION_CHANGED,
                    "calling convention changed (explicit state)",
                    oldS.callingConvention, newS.callingConvention);
        }
        if (!Objects.equals(bool(oldS.variadic), bool(newS.variadic))) {
            add(changes, rules, ChangeKind.VARIADIC_CHANGED,
                    "variadic flag changed (explicit state)",
                    String.valueOf(bool(oldS.variadic)), String.valueOf(bool(newS.variadic)));
        }
        if (!Objects.equals(str(oldS.returnType), str(newS.returnType))) {
            add(changes, rules, ChangeKind.RETURN_TYPE_CHANGED, "return type changed",
                    oldS.returnType, newS.returnType);
        }
        if (!Objects.equals(str(oldS.versionTag), str(newS.versionTag))) {
            add(changes, rules, ChangeKind.VERSION_TAG_CHANGED, "version tag/map changed",
                    oldS.versionTag, newS.versionTag);
        }

        compareParameters(oldS, newS, rules, changes);
        compareAliases(oldS, newS, rules, changes);
        return changes;
    }

    private static void compareParameters(AbiSymbol oldS, AbiSymbol newS, RuleSet rules,
                                          List<Change> changes) {
        List<Parameter> oldP = oldS.parameters == null ? List.of() : oldS.parameters;
        List<Parameter> newP = newS.parameters == null ? List.of() : newS.parameters;
        if (oldP.size() != newP.size()) {
            add(changes, rules, ChangeKind.PARAMETER_CHANGED,
                    "parameter count changed",
                    oldP.size() + " params", newP.size() + " params", null, null);
        }
        int n = Math.min(oldP.size(), newP.size());
        for (int i = 0; i < n; i++) {
            Parameter a = oldP.get(i);
            Parameter b = newP.get(i);
            String member = b.name != null ? b.name : a.name;
            if (!Objects.equals(str(a.type), str(b.type))) {
                add(changes, rules, ChangeKind.PARAMETER_CHANGED,
                        "parameter type changed at index " + i,
                        str(a.type), str(b.type), member, null);
            }
        }
    }

    private static void compareAliases(AbiSymbol oldS, AbiSymbol newS, RuleSet rules,
                                       List<Change> changes) {
        Set<String> oldA = new LinkedHashSet<>(oldS.aliases == null ? List.of() : oldS.aliases);
        Set<String> newA = new LinkedHashSet<>(newS.aliases == null ? List.of() : newS.aliases);
        for (String alias : newA) {
            if (!oldA.contains(alias)) {
                Change c = add(changes, rules, ChangeKind.ALIAS_ADDED,
                        "platform alias added", null, alias, alias, null);
                c.paths = new ArrayList<>();
            }
        }
        for (String alias : oldA) {
            if (!newA.contains(alias)) {
                add(changes, rules, ChangeKind.ALIAS_REMOVED,
                        "platform alias removed (existing callers may fail to bind)",
                        alias, null, alias, null);
            }
        }
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

    private static boolean bool(Boolean b) {
        return b != null && b;
    }
}
