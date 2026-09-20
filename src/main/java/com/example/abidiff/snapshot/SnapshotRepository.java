package com.example.abidiff.snapshot;

import com.example.abidiff.json.Docs;
import com.example.abidiff.json.Json;
import com.example.abidiff.model.Enums;
import com.example.abidiff.model.SemVer;
import com.example.abidiff.model.StoredTypes.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/**
 * Stores releases, content-hash-deduped snapshots and their flattened symbols,
 * aliases and reference edges.
 */
@Repository
public class SnapshotRepository {

    private final JdbcTemplate jdbc;

    public SnapshotRepository(JdbcTemplate snapshotJdbc) {
        this.jdbc = snapshotJdbc;
    }

    public static String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Canonical bytes: re-serialized with sorted keys, so formatting never affects identity. */
    public static String canonicalHash(String rawJson) {
        try {
            JsonNode tree = Json.MAPPER.readTree(rawJson);
            byte[] canon = Json.MAPPER.writeValueAsBytes(tree);
            return sha256(canon);
        } catch (Exception e) {
            throw new IllegalArgumentException("snapshot document is not valid JSON", e);
        }
    }

    @Transactional("snapshotTransactionManager")
    public SnapshotRow importSnapshot(String dist, String version, String rawJson) {
        Docs.SnapshotDoc doc = Json.read(rawJson, Docs.SnapshotDoc.class);
        validate(doc);
        SemVer v = SemVer.parse(version);
        String releaseId = "rel-" + dist + "-" + v;
        String contentHash = canonicalHash(rawJson);

        List<String> existingIds = jdbc.queryForList(
                "SELECT id FROM snapshot WHERE content_hash = ?", String.class, contentHash);
        if (!existingIds.isEmpty()) {
            String existing = existingIds.get(0);
            return getSnapshot(existing);
        }

        String now = Instant.now().toString();
        jdbc.update("INSERT INTO release(id, dist, version, version_major, version_minor, version_patch, imported_at) "
                        + "VALUES(?,?,?,?,?,?,?) ON CONFLICT(dist, version) DO NOTHING",
                releaseId, dist, v.toString(), v.major(), v.minor(), v.patch(), now);

        String snapshotId = "snap-" + contentHash.substring(0, 16);
        jdbc.update("INSERT INTO snapshot(id, release_id, platform, arch, extractor, extractor_version, "
                        + "content_hash, raw_json, created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                snapshotId, releaseId, doc.platform(), doc.arch(),
                doc.extractor().name(), doc.extractor().version(), contentHash,
                Json.write(doc), now);

        int ordinal = 0;
        for (Docs.ComponentDoc c : nullSafe(doc.components())) {
            jdbc.update("INSERT INTO component(snapshot_id, stable_id, name, kind, boundary, ordinal) "
                            + "VALUES(?,?,?,?,?,?)",
                    snapshotId, c.stable_id(), c.name(), c.kind(), c.boundary(), ordinal++);
        }
        ordinal = 0;
        for (Docs.SymbolDoc s : nullSafe(doc.symbols())) {
            String detailJson = Json.write(s.detail() == null ? Map.of() : s.detail());
            jdbc.update("INSERT INTO symbol(snapshot_id, component_stable_id, stable_id, name, kind, boundary, "
                            + "visibility, binding, calling_convention, variadic, size, align, zero_sized, "
                            + "detail_json, ordinal) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    snapshotId, cStr(s.component()), s.stable_id(), s.name(), s.kind(), s.boundary(),
                    s.visibility(), s.binding(), s.calling_convention(),
                    Boolean.TRUE.equals(s.variadicFlag()) ? 1 : 0,
                    s.size(), s.align(), Boolean.TRUE.equals(s.zeroFlag()) ? 1 : 0,
                    detailJson, ordinal++);
        }
        for (Docs.AliasDoc a : nullSafe(doc.aliases())) {
            jdbc.update("INSERT INTO symbol_alias(snapshot_id, stable_id, alias, alias_kind) VALUES(?,?,?,?)",
                    snapshotId, a.stable_id(), a.alias(), a.kind());
        }
        for (Docs.RefDoc r : nullSafe(doc.refs())) {
            jdbc.update("INSERT INTO symbol_ref(snapshot_id, src_stable, dst_stable, ref_kind) VALUES(?,?,?,?)",
                    snapshotId, r.from(), r.to(), r.kind());
        }
        return getSnapshot(snapshotId);
    }

    private void validate(Docs.SnapshotDoc doc) {
        if (doc.platform() == null || doc.arch() == null) {
            throw new IllegalArgumentException("snapshot requires platform and arch");
        }
        if (doc.extractor() == null || doc.extractor().name() == null) {
            throw new IllegalArgumentException("snapshot requires extractor.name (provenance)");
        }
        Set<String> ids = new HashSet<>();
        for (Docs.SymbolDoc s : nullSafe(doc.symbols())) {
            if (s.stable_id() == null || s.name() == null || s.kind() == null) {
                throw new IllegalArgumentException("symbol requires stable_id, name and kind");
            }
            if (!ids.add(s.stable_id())) {
                throw new IllegalArgumentException("duplicate stable_id in snapshot: " + s.stable_id());
            }
            if (!Enums.Platform.UNKNOWN.equals(Enums.Platform.of(doc.platform()))) {
                // valid code
            }
        }
    }

    private static String cStr(String s) {
        return s == null ? "" : s;
    }

    private static <T> List<T> nullSafe(List<T> l) {
        return l == null ? List.of() : l;
    }

    public SnapshotRow getSnapshot(String id) {
        return jdbc.queryForObject("SELECT id, release_id, platform, arch, extractor, extractor_version, "
                + "content_hash, raw_json, created_at FROM snapshot WHERE id = ?", SNAPSHOT_ROW, id);
    }

    public LoadedSnapshot load(String snapshotId) {
        SnapshotRow snap = getSnapshot(snapshotId);
        List<ComponentRow> components = jdbc.query(
                "SELECT stable_id, name, kind, boundary FROM component WHERE snapshot_id=? ORDER BY ordinal",
                COMPONENT_ROW, snapshotId);
        List<SymbolRow> symbols = jdbc.query(
                "SELECT component_stable_id, stable_id, name, kind, boundary, visibility, binding, "
                        + "calling_convention, variadic, size, align, zero_sized, detail_json "
                        + "FROM symbol WHERE snapshot_id=? ORDER BY ordinal",
                SYMBOL_ROW, snapshotId);
        List<AliasRow> aliases = jdbc.query(
                "SELECT stable_id, alias, alias_kind FROM symbol_alias WHERE snapshot_id=?",
                ALIAS_ROW, snapshotId);
        List<RefRow> refs = jdbc.query(
                "SELECT src_stable, dst_stable, ref_kind FROM symbol_ref WHERE snapshot_id=?",
                REF_ROW, snapshotId);
        return new LoadedSnapshot(snap, components, symbols, aliases, refs);
    }

    public List<Map<String, Object>> listReleases() {
        return jdbc.queryForList(
                "SELECT id, dist, version, imported_at FROM release ORDER BY version_major, version_minor, version_patch");
    }

    public List<Map<String, Object>> listSnapshots(String releaseId) {
        return jdbc.queryForList(
                "SELECT id, release_id, platform, arch, extractor, extractor_version, content_hash, created_at "
                        + "FROM snapshot WHERE release_id=? ORDER BY platform, arch", releaseId);
    }

    public List<Map<String, Object>> listAllSnapshots() {
        return jdbc.queryForList(
                "SELECT s.id, s.release_id, s.platform, s.arch, s.extractor, s.extractor_version, "
                        + "s.content_hash, s.created_at, r.dist, r.version "
                        + "FROM snapshot s JOIN release r ON r.id = s.release_id ORDER BY r.version, s.platform");
    }

    private static final RowMapper<SnapshotRow> SNAPSHOT_ROW = (rs, n) -> new SnapshotRow(
            rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
            rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9));

    private static final RowMapper<ComponentRow> COMPONENT_ROW = (rs, n) -> new ComponentRow(
            rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4));

    private static final RowMapper<SymbolRow> SYMBOL_ROW = (rs, n) -> {
        JsonNode detail = Json.tree(rs.getString(13));
        String kind = rs.getString(4);
        Docs.RecordDetail record = "TYPE_RECORD".equals(kind)
                ? Json.convert(detail, Docs.RecordDetail.class) : null;
        Docs.FunctionDetail function = "FUNCTION".equals(kind)
                ? Json.convert(detail, Docs.FunctionDetail.class) : null;
        Docs.EnumDetail enumDetail = "TYPE_ENUM".equals(kind)
                ? Json.convert(detail, Docs.EnumDetail.class) : null;
        return new SymbolRow(rs.getString(1), rs.getString(2), rs.getString(3),
                kind, rs.getString(5), rs.getString(6), rs.getString(7),
                rs.getString(8), rs.getInt(9) == 1,
                rs.getObject(10) == null ? null : ((Number) rs.getObject(10)).longValue(),
                rs.getObject(11) == null ? null : ((Number) rs.getObject(11)).longValue(), rs.getInt(12) == 1,
                record, function, enumDetail);
    };

    private static final RowMapper<AliasRow> ALIAS_ROW = (rs, n) -> new AliasRow(
            rs.getString(1), rs.getString(2), Enums.AliasKind.valueOf(rs.getString(3)));

    private static final RowMapper<RefRow> REF_ROW = (rs, n) -> new RefRow(
            rs.getString(1), rs.getString(2), Enums.RefKind.valueOf(rs.getString(3)));
}
