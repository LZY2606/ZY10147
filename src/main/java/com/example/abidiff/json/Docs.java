package com.example.abidiff.json;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Intermediate model for extractor exports. The application deliberately does
 * not parse real ELF / Mach-O / PE files; platforms' build pipelines emit these
 * JSON documents instead.
 *
 * <p>Identity contract: every symbol carries an explicit {@code stable_id}.
 * Cross-platform name differences are reconciled only via stable_id (or an
 * explicit alias); name similarity by itself is never treated as a rename.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class Docs {
    private Docs() {
    }

    public record SnapshotDoc(String platform, String arch, ExtractorDoc extractor,
                              List<ComponentDoc> components, List<SymbolDoc> symbols,
                              List<AliasDoc> aliases, List<RefDoc> refs) {
        public SnapshotDoc {
            components = components == null ? List.of() : components;
            symbols = symbols == null ? List.of() : symbols;
            aliases = aliases == null ? List.of() : aliases;
            refs = refs == null ? List.of() : refs;
        }
    }

    public record ExtractorDoc(String name, String version, String source) {
    }

    public record ComponentDoc(String stable_id, String name, String kind, String boundary) {
    }

    /** Polymorphic detail ({@code detail}) is kept as an opaque node and converted on read. */
    public record SymbolDoc(String stable_id, String component, String name, String kind,
                            String boundary, String visibility, String binding,
                            String calling_convention, Boolean variadic,
                            Long size, Long align, Boolean zero_sized,
                            com.fasterxml.jackson.databind.JsonNode detail) {
        public boolean variadicFlag() {
            return Boolean.TRUE.equals(variadic);
        }
        public boolean zeroFlag() {
            return Boolean.TRUE.equals(zero_sized);
        }
    }

    public record RecordDetail(String record_kind, String tag, List<FieldDoc> fields,
                               Boolean tail_padding, Long tail_padding_bytes) {
        public RecordDetail {
            fields = fields == null ? List.of() : fields;
        }
    }

    public record FieldDoc(String stable_id, String name, String type, Long offset, Long size,
                           Long align, Boolean reserved, String reserved_id,
                           Boolean bitfield, Long bitfield_offset_bits, Long bitfield_width_bits,
                           Boolean tail) {
        public boolean reservedFlag() {
            return Boolean.TRUE.equals(reserved);
        }
        public boolean bitfieldFlag() {
            return Boolean.TRUE.equals(bitfield);
        }
        public boolean tailFlag() {
            return Boolean.TRUE.equals(tail);
        }
    }

    public record FunctionDetail(String return_type, List<ParamDoc> params) {
        public FunctionDetail {
            params = params == null ? List.of() : params;
        }
    }

    public record ParamDoc(String name, String type) {
    }

    public record EnumDetail(String underlying_type, List<EnumValueDoc> values) {
        public EnumDetail {
            values = values == null ? List.of() : values;
        }
    }

    public record EnumValueDoc(String name, Long value) {
    }

    public record AliasDoc(String stable_id, String alias, String kind) {
    }

    public record RefDoc(String from, String to, String kind) {
    }
}
