package com.abi.compare.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class Extractor {
    public String name;
    public String version;

    public Extractor() {
    }

    public Extractor(String name, String version) {
        this.name = name;
        this.version = version;
    }
}
