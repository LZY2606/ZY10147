package com.abi.compare.rule;

import com.abi.compare.diff.ChangeKind;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Built-in rule sets, keyed by platform/arch/boundary. */
@Component
public class RuleRegistry {

    private final Map<String, RuleSet> ruleSets = new LinkedHashMap<>();

    public RuleRegistry() {
        register(linuxAarch64Public("1.0"));
        register(linuxAarch64Public("2.0"));
        register(windowsAmd64Public("1.0"));
        register(darwinArm64Public("1.0"));
        register(linuxAarch64Private("1.0"));
    }

    public Map<String, RuleSet> all() {
        return ruleSets;
    }

    public Optional<RuleSet> find(String id) {
        return Optional.ofNullable(ruleSets.get(id));
    }

    private void register(RuleSet ruleSet) {
        ruleSets.put(ruleSet.id, ruleSet);
    }

    private static Map<String, Severity> commonDefaultSeverities() {
        Map<String, Severity> m = new LinkedHashMap<>();
        m.put(ChangeKind.SYMBOL_ADDED, Severity.COMPATIBLE);
        m.put(ChangeKind.SYMBOL_REMOVED, Severity.BREAKING);
        m.put(ChangeKind.SYMBOL_RENAMED, Severity.BREAKING);
        m.put(ChangeKind.ALIAS_ADDED, Severity.INFO);
        m.put(ChangeKind.ALIAS_REMOVED, Severity.WARNING);
        m.put(ChangeKind.VISIBILITY_CHANGED, Severity.BREAKING);
        m.put(ChangeKind.LINKAGE_CHANGED, Severity.BREAKING);
        m.put(ChangeKind.CALLING_CONVENTION_CHANGED, Severity.BREAKING);
        m.put(ChangeKind.VARIADIC_CHANGED, Severity.BREAKING);
        m.put(ChangeKind.PARAMETER_CHANGED, Severity.BREAKING);
        m.put(ChangeKind.RETURN_TYPE_CHANGED, Severity.BREAKING);
        m.put(ChangeKind.VERSION_TAG_CHANGED, Severity.WARNING);

        m.put(ChangeKind.TYPE_ADDED, Severity.COMPATIBLE);
        m.put(ChangeKind.TYPE_REMOVED, Severity.BREAKING);
        m.put(ChangeKind.TYPE_SIZE_CHANGED, Severity.WARNING);
        m.put(ChangeKind.TYPE_ALIGN_CHANGED, Severity.BREAKING);
        m.put(ChangeKind.TAIL_PADDING_CHANGED, Severity.WARNING);
        m.put(ChangeKind.FIELD_OFFSET_CHANGED, Severity.BREAKING);
        m.put(ChangeKind.FIELD_TYPE_CHANGED, Severity.BREAKING);
        m.put(ChangeKind.FIELD_BITFIELD_CHANGED, Severity.BREAKING);
        m.put(ChangeKind.FIELD_INSERTED_MIDDLE, Severity.BREAKING);
        m.put(ChangeKind.FIELD_REMOVED, Severity.BREAKING);
        m.put(ChangeKind.FIELD_APPENDED_PUBLIC, Severity.BREAKING);
        m.put(ChangeKind.FIELD_APPENDED_PRIVATE_TAIL, Severity.WARNING);
        m.put(ChangeKind.PADDING_REUSED, Severity.WARNING);
        m.put(ChangeKind.ZERO_SIZE_CHANGED, Severity.WARNING);
        return m;
    }

    private RuleSet base(String id, String version, String platform, String arch,
                         String boundary, String description) {
        RuleSet rs = new RuleSet();
        rs.id = id;
        rs.version = version;
        rs.platform = platform;
        rs.arch = arch;
        rs.boundary = boundary;
        rs.description = description;
        rs.severities = commonDefaultSeverities();
        return rs;
    }

    private RuleSet linuxAarch64Public(String version) {
        String id = "2.0".equals(version)
                ? "linux-aarch64-public-v2" : "linux-aarch64-public";
        RuleSet rs = base(id, version, "linux", "aarch64", "PUBLIC",
                "Linux/AArch64 public symbol + struct layout rules");
        if ("2.0".equals(version)) {
            // Rule evolution: tail padding consumption is treated as BREAKING in v2.
            rs.severities.put(ChangeKind.PADDING_REUSED, Severity.BREAKING);
            rs.severities.put(ChangeKind.TAIL_PADDING_CHANGED, Severity.BREAKING);
            rs.description += " (v2: reserved padding reuse is breaking)";
        }
        return rs;
    }

    private RuleSet windowsAmd64Public(String version) {
        RuleSet rs = base("windows-amd64-public", version, "windows", "amd64", "PUBLIC",
                "Windows/x64 public ABI rules (stdcall/ms convention sensitive)");
        // Private tail append still flips sizeof for an MSVC public struct family.
        rs.severities.put(ChangeKind.FIELD_APPENDED_PRIVATE_TAIL, Severity.BREAKING);
        return rs;
    }

    private RuleSet darwinArm64Public(String version) {
        return base("darwin-arm64-public", version, "darwin", "arm64", "PUBLIC",
                "macOS/arm64 public ABI rules");
    }

    private RuleSet linuxAarch64Private(String version) {
        RuleSet rs = base("linux-aarch64-private", version, "linux", "aarch64", "PRIVATE",
                "Linux/AArch64 internal boundary: private layout changes are tolerated");
        rs.severities.put(ChangeKind.FIELD_APPENDED_PRIVATE_TAIL, Severity.COMPATIBLE);
        rs.severities.put(ChangeKind.TYPE_SIZE_CHANGED, Severity.COMPATIBLE);
        rs.severities.put(ChangeKind.TAIL_PADDING_CHANGED, Severity.INFO);
        rs.severities.put(ChangeKind.ZERO_SIZE_CHANGED, Severity.INFO);
        return rs;
    }
}
