package com.abi.compare.diff;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/** Full result of comparing two snapshots under one rule set. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DiffResult {
    public String id;
    @JsonProperty("oldSnapshotHash")
    public String oldSnapshotHash;
    @JsonProperty("newSnapshotHash")
    public String newSnapshotHash;
    public String component;
    @JsonProperty("oldRelease")
    public String oldRelease;
    @JsonProperty("newRelease")
    public String newRelease;
    public String platform;
    public String arch;
    @JsonProperty("ruleSetId")
    public String ruleSetId;
    @JsonProperty("ruleSetVersion")
    public String ruleSetVersion;
    public String createdAt;
    public long version;
    public Map<String, Integer> counts;
    public List<EntryChange> symbols;
    public List<EntryChange> types;
    /** Public entry stableIds reachable from a change, with impact paths. */
    @JsonProperty("affectedPublicEntries")
    public List<EntryChange> affectedPublicEntries;
}
