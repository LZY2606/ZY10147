package com.abi.compare.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/** Struct/union/typedef layout as extracted from debug info or headers. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AbiType {
    @JsonProperty("stableId")
    public String stableId;
    public String kind;
    public String name;
    public long size;
    public long alignment;
    /** Trailing padding in bytes (layout fact; defaults to computed value). */
    @JsonProperty("tailPadding")
    public Long tailPadding;
    /** Explicit zero-sized-type marker (zero-sized types carry explicit state). */
    @JsonProperty("zeroSized")
    public Boolean zeroSized;
    public List<Field> fields = new ArrayList<>();
}
