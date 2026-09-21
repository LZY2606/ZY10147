package com.abi.compare.rule;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.HashMap;
import java.util.Map;

/**
 * Platform/arch/boundary scoped rules. The same snapshot pair produces
 * different, independently stored results under different rule sets.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RuleSet {
    public String id;
    public String version;
    public String platform;
    public String arch;
    /** PUBLIC or PRIVATE boundary focus. */
    public String boundary;
    public String description;
    /** changeKind -> severity override. */
    @JsonProperty("severities")
    public Map<String, Severity> severities = new HashMap<>();

    public Severity severityFor(String changeKind, Severity builtinDefault) {
        Severity override = severities.get(changeKind);
        return override != null ? override : builtinDefault;
    }
}
