package com.abi.compare.diff;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class DecisionView {
    public String platform;
    public String verdict;
    public String rationale;
    @JsonProperty("effectiveFrom")
    public String effectiveFrom;
    @JsonProperty("expiresAfterVersion")
    public String expiresAfterVersion;
    public String status;
    @JsonProperty("decidedAt")
    public String decidedAt;
    @JsonProperty("baseVersion")
    public Long baseVersion;
    @JsonProperty("changeKind")
    public String changeKind;

    public boolean changeKindRefMatches(Change change) {
        return changeKind == null || changeKind.isBlank() || changeKind.equals(change.kind);
    }
}
