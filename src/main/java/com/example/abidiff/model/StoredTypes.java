package com.example.abidiff.model;

import com.example.abidiff.json.Docs;

import java.util.List;

/** Row-level views over stored snapshot content. */
public final class StoredTypes {
    private StoredTypes() {
    }

    public record ReleaseRow(String id, String dist, String version, String importedAt) {
    }

    public record SnapshotRow(String id, String releaseId, String platform, String arch,
                              String extractor, String extractorVersion,
                              String contentHash, String rawJson, String createdAt) {
    }

    public record ComponentRow(String stableId, String name, String kind, String boundary) {
    }

    public record SymbolRow(String componentStableId, String stableId, String name,
                            String kind, String boundary, String visibility, String binding,
                            String callingConvention, boolean variadic,
                            Long size, Long align, boolean zeroSized,
                            Docs.RecordDetail record, Docs.FunctionDetail function,
                            Docs.EnumDetail enumDetail) {
    }

    public record AliasRow(String stableId, String alias, Enums.AliasKind kind) {
    }

    public record RefRow(String from, String to, Enums.RefKind kind) {
    }

    public record LoadedSnapshot(SnapshotRow snapshot, List<ComponentRow> components,
                                 List<SymbolRow> symbols, List<AliasRow> aliases,
                                 List<RefRow> refs) {
    }
}
