package com.abi.compare.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A human verdict kept strictly separate from raw snapshots.
 *
 * <p>A decision can be scoped globally ({@code platform="*"}) or to a single
 * platform, so two platforms may keep different conclusions. It has an
 * explicit validity range ({@code effectiveFrom}..{@code expiresAfterVersion}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Decision {
    public Long id;
    @JsonProperty("comparisonId")
    public String comparisonId;
    @JsonProperty("stableId")
    public String stableId;
    @JsonProperty("changeKind")
    public String changeKind;
    /** "*" or a platform name. */
    public String platform;
    /** ACCEPT (exception approved) / REJECT (breaking confirmed). */
    public String verdict;
    public String rationale;
    @JsonProperty("effectiveFrom")
    public String effectiveFrom;
    @JsonProperty("expiresAfterVersion")
    public String expiresAfterVersion;
    @JsonProperty("baseVersion")
    public Long baseVersion;
    @JsonProperty("decidedAt")
    public String decidedAt;
}
