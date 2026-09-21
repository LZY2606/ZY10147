package com.abi.compare.diff;

/** Vocabulary of atomic ABI changes the engine can emit. */
public final class ChangeKind {
    private ChangeKind() {
    }

    public static final String SYMBOL_ADDED = "SYMBOL_ADDED";
    public static final String SYMBOL_REMOVED = "SYMBOL_REMOVED";
    public static final String SYMBOL_RENAMED = "SYMBOL_RENAMED";
    public static final String ALIAS_ADDED = "ALIAS_ADDED";
    public static final String ALIAS_REMOVED = "ALIAS_REMOVED";
    public static final String VISIBILITY_CHANGED = "VISIBILITY_CHANGED";
    public static final String LINKAGE_CHANGED = "LINKAGE_CHANGED";
    public static final String CALLING_CONVENTION_CHANGED = "CALLING_CONVENTION_CHANGED";
    public static final String VARIADIC_CHANGED = "VARIADIC_CHANGED";
    public static final String PARAMETER_CHANGED = "PARAMETER_CHANGED";
    public static final String RETURN_TYPE_CHANGED = "RETURN_TYPE_CHANGED";
    public static final String VERSION_TAG_CHANGED = "VERSION_TAG_CHANGED";

    public static final String TYPE_SIZE_CHANGED = "TYPE_SIZE_CHANGED";
    public static final String TYPE_ALIGN_CHANGED = "TYPE_ALIGN_CHANGED";
    public static final String TAIL_PADDING_CHANGED = "TAIL_PADDING_CHANGED";
    public static final String FIELD_OFFSET_CHANGED = "FIELD_OFFSET_CHANGED";
    public static final String FIELD_TYPE_CHANGED = "FIELD_TYPE_CHANGED";
    public static final String FIELD_BITFIELD_CHANGED = "FIELD_BITFIELD_CHANGED";
    public static final String FIELD_INSERTED_MIDDLE = "FIELD_INSERTED_MIDDLE";
    public static final String FIELD_REMOVED = "FIELD_REMOVED";
    public static final String FIELD_APPENDED_PUBLIC = "FIELD_APPENDED_PUBLIC";
    public static final String FIELD_APPENDED_PRIVATE_TAIL = "FIELD_APPENDED_PRIVATE_TAIL";
    public static final String PADDING_REUSED = "PADDING_REUSED";
    public static final String ZERO_SIZE_CHANGED = "ZERO_SIZE_CHANGED";
    public static final String TYPE_ADDED = "TYPE_ADDED";
    public static final String TYPE_REMOVED = "TYPE_REMOVED";
}
