package com.abi.compare.diff;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** One atomic change attached to a symbol or type. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Change {
    public String kind;
    public String severity;
    public String detail;
    public String from;
    public String to;
    /** Field/parameter name when the change is scoped to one member. */
    public String member;
    /** stableIds that were paired by an explicit stable id but differ in name. */
    @JsonProperty("evidence")
    public String evidence;
    @JsonProperty("exceptionRef")
    public String exceptionRef;
    public List<String> paths;

    public Change() {
    }

    public Change(String kind, String severity, String detail) {
        this.kind = kind;
        this.severity = severity;
        this.detail = detail;
    }
}
