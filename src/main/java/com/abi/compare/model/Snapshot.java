package com.abi.compare.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * One extractor/platform export for one component release.
 *
 * <p>JSON shape (see README):
 * <pre>
 * {
 *   "schemaVersion": 1,
 *   "component": "libwidget",
 *   "release": "1.4.0",
 *   "platform": "linux",
 *   "arch": "aarch64",
 *   "extractor": {"name":"readelf-abi","version":"2.1"},
 *   "symbols": [...], "types": [...]
 * }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Snapshot {
    @JsonProperty("schemaVersion")
    public int schemaVersion;
    public String component;
    public String release;
    public String platform;
    public String arch;
    public Extractor extractor;
    public List<AbiSymbol> symbols = new ArrayList<>();
    public List<AbiType> types = new ArrayList<>();
    @JsonProperty("contentHash")
    public String contentHash;
}
