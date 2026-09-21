package com.abi.compare.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/** One function parameter. Type names are qualified type stable identifiers. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Parameter {
    public String name;
    public String type;

    public Parameter() {
    }

    public Parameter(String name, String type) {
        this.name = name;
        this.type = type;
    }
}
