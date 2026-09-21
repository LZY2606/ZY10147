package com.abi.compare.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Struct/union field with explicit layout facts. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Field {
    /** Field logical name (stable within a type). */
    public String name;
    public String type;
    public long offset;
    public long size;
    @JsonProperty("bitOffset")
    public Integer bitOffset;
    @JsonProperty("bitWidth")
    public Integer bitWidth;
    public Boolean privateField;
    @JsonProperty("reservedPadding")
    public Boolean reservedPadding;
    public String note;
}
