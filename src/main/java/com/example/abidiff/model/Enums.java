package com.example.abidiff.model;

/** Central enumerations shared by storage, diff engine and rules. */
public final class Enums {
    private Enums() {
    }

    public enum Platform {
        LINUX("linux"), WINDOWS("windows"), MACOS("macos"), UNKNOWN("unknown");
        public final String code;
        Platform(String code) {
            this.code = code;
        }
        public static Platform of(String code) {
            for (Platform p : values()) {
                if (p.code.equalsIgnoreCase(code)) {
                    return p;
                }
            }
            return UNKNOWN;
        }
    }

    public enum Boundary { PUBLIC, PRIVATE }

    public enum Visibility { DEFAULT, HIDDEN, PROTECTED, EXPORT, STATIC_LOCAL }

    public enum SymbolKind { FUNCTION, VARIABLE, TYPE_RECORD, TYPE_ENUM, TYPE_ZERO, TYPEDEF }

    public enum RecordKind { STRUCT, UNION }

    public enum AliasKind { EXPLICIT, WEAK_ALIAS, PLATFORM_NAME, SONAME_LINK }

    public enum RefKind { CALL, PARAM, RETURN, FIELD, DERIVES, REEXPORT }

    public enum Cc { C, STDCALL, CDECL, FASTCALL, AAPCS, AAPCS_VFP, SYSV, MS, VECTORCALL, UNKNOWN;
        public static Cc of(String s) {
            if (s == null) return null;
            for (Cc c : values()) {
                if (c.name().equalsIgnoreCase(s.replace('-', '_'))) {
                    return c;
                }
            }
            return UNKNOWN;
        }
    }

    /** Broad change bucket for grouping in the UI. */
    public enum Category { ADDED, REMOVED, RENAMED, LAYOUT, SIGNATURE, VISIBILITY, GRAPH }

    /** Rule severities; ALLOWED marks an expected, rule-sanctioned change. */
    public enum Severity { BREAKING, COMPATIBLE, INFO, ALLOWED }

    public enum DecisionKind { EXCEPTION, ACCEPT_BREAK, REJECT_RELEASE }
}
