package com.abi.compare.diff;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/** All changes for one matched symbol or one unpaired add/remove entry. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EntryChange {
    public String category;
    @JsonProperty("stableId")
    public String stableId;
    @JsonProperty("oldName")
    public String oldName;
    @JsonProperty("newName")
    public String newName;
    public String kind;
    public String linkage;
    public String visibility;
    public List<Change> changes = new ArrayList<>();
    /** For reference-graph pages: why/how an entry is impacted. */
    @JsonProperty("impactPaths")
    public List<List<String>> impactPaths = new ArrayList<>();
    /** Resolved effective decisions keyed by platform ("*" = global). */
    @JsonProperty("decisions")
    public List<DecisionView> decisions = new ArrayList<>();
}
