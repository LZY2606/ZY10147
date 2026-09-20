package com.example.abidiff.rules;

/** Stable change-kind identifiers produced by the diff engine and keyed in rules. */
public final class ChangeKinds {
    private ChangeKinds() {
    }

    // symbol lifecycle
    public static final String SYMBOL_ADDED = "SYMBOL_ADDED";
    public static final String SYMBOL_REMOVED = "SYMBOL_REMOVED";
    public static final String SYMBOL_RENAMED = "SYMBOL_RENAMED";
    public static final String VISIBILITY_REDUCED = "VISIBILITY_REDUCED";
    public static final String VISIBILITY_RAISED = "VISIBILITY_RAISED";

    // signature / call convention
    public static final String CC_CHANGED = "CC_CHANGED";
    public static final String CC_VARARGS_TOGGLED = "CC_VARARGS_TOGGLED";
    public static final String PARAM_TYPE_CHANGED = "PARAM_TYPE_CHANGED";
    public static final String PARAM_ADDED = "PARAM_ADDED";
    public static final String PARAM_REMOVED = "PARAM_REMOVED";
    public static final String RETURN_TYPE_CHANGED = "RETURN_TYPE_CHANGED";

    // data / types
    public static final String VAR_TYPE_CHANGED = "VAR_TYPE_CHANGED";
    public static final String ZST_TO_SIZED = "ZST_TO_SIZED";
    public static final String SIZED_TO_ZST = "SIZED_TO_ZST";

    // struct layout
    public static final String LAYOUT_SIZE_CHANGED = "LAYOUT_SIZE_CHANGED";
    public static final String LAYOUT_ALIGN_CHANGED = "LAYOUT_ALIGN_CHANGED";
    public static final String LAYOUT_FIELD_INSERTED = "LAYOUT_FIELD_INSERTED";
    public static final String LAYOUT_FIELD_REMOVED = "LAYOUT_FIELD_REMOVED";
    public static final String LAYOUT_FIELD_OFFSET = "LAYOUT_FIELD_OFFSET";
    public static final String LAYOUT_FIELD_TYPE = "LAYOUT_FIELD_TYPE";
    public static final String LAYOUT_RESERVED_REUSED = "LAYOUT_RESERVED_REUSED";
    public static final String LAYOUT_TAIL_PRIVATE_ADDED = "LAYOUT_TAIL_PRIVATE_ADDED";
    public static final String LAYOUT_TAIL_PADDING_ELIDED = "LAYOUT_TAIL_PADDING_ELIDED";
    public static final String BITFIELD_LAYOUT_CHANGED = "BITFIELD_LAYOUT_CHANGED";
}
