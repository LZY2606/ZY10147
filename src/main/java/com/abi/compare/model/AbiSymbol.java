package com.abi.compare.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * An exported or imported symbol (function or data) as seen on one platform.
 *
 * <p>{@code stableId} is the only cross-platform correspondence evidence: two
 * platform-mangled names are the same logical symbol iff their stable ids match.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AbiSymbol {
    /** Logical cross-platform identifier, e.g. "libwidget.widget_init". */
    @JsonProperty("stableId")
    public String stableId;
    /** Platform name as emitted by the toolchain (possibly mangled). */
    public String name;
    public String kind;
    /** EXPORTED or IMPORTED. */
    public String linkage;
    /** PUBLIC, HIDDEN, PROTECTED, ... */
    public String visibility;
    /** cdecl, stdcall, aarch64-aapcs, sysv, ms, ... */
    @JsonProperty("callingConvention")
    public String callingConvention;
    /** cdecl, stdcall, aarch64-aapcs, sysv, ms, ... */
    public Boolean variadic;
    @JsonProperty("returnType")
    public String returnType;
    public List<Parameter> parameters = new ArrayList<>();
    /** Version tag/map, e.g. "LIBWIDGET_1.2" or PE/@rpath version. */
    @JsonProperty("versionTag")
    public String versionTag;
    /** Alias platform names (weak aliases, symbol symvers, #pragma comment aliases). */
    public List<String> aliases = new ArrayList<>();
    /** stableIds this body references (direct call / data use). */
    public List<String> references = new ArrayList<>();
    public Boolean deprecated;
    public String note;
}
