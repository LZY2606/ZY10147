package com.example.abidiff.rules;

import com.example.abidiff.json.Json;
import com.example.abidiff.model.Enums.Boundary;
import com.example.abidiff.model.Enums.Severity;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class RuleRepository {

    private static final TypeReference<Map<Boundary, Map<String, Severity>>> RULES_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;

    public RuleRepository(JdbcTemplate snapshotJdbc) {
        this.jdbc = snapshotJdbc;
    }

    @Transactional("snapshotTransactionManager")
    public void insertIfAbsent(RuleSet rs) {
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ruleset WHERE platform=? AND arch=? AND revision=?",
                Integer.class, rs.platform(), rs.arch(), rs.revision());
        if (exists != null && exists > 0) {
            return;
        }
        jdbc.update("INSERT INTO ruleset(id, platform, arch, revision, is_active, note, rules_json, created_at) "
                        + "VALUES(?,?,?,?,?,?,?,?)",
                rs.id(), rs.platform(), rs.arch(), rs.revision(), rs.active() ? 1 : 0,
                rs.note(), Json.write(rs.rules()), Instant.now().toString());
    }

    public RuleSet active(String platform, String arch) {
        return all().stream()
                .filter(r -> r.platform().equals(platform) && r.arch().equals(arch) && r.active())
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "no active ruleset for " + platform + "/" + arch));
    }

    public RuleSet byId(String id) {
        return jdbc.queryForObject(
                "SELECT id, platform, arch, revision, is_active, note, rules_json FROM ruleset WHERE id=?",
                (rs, n) -> mapRow(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getInt(4), rs.getInt(5) == 1, rs.getString(6), rs.getString(7)),
                id);
    }

    public List<RuleSet> all() {
        return jdbc.query(
                "SELECT id, platform, arch, revision, is_active, note, rules_json "
                        + "FROM ruleset ORDER BY platform, arch, revision",
                (rs, n) -> mapRow(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getInt(4), rs.getInt(5) == 1, rs.getString(6), rs.getString(7)));
    }

    private RuleSet mapRow(String id, String platform, String arch, int revision,
                           boolean active, String note, String rulesJson) {
        Map<Boundary, Map<String, Severity>> raw =
                Json.MAPPER.convertValue(Json.tree(rulesJson), RULES_TYPE);
        Map<Boundary, Map<String, Severity>> rules = new LinkedHashMap<>();
        raw.forEach((b, m) -> rules.put(b, new LinkedHashMap<>(m)));
        return new RuleSet(id, platform, arch, revision, active, note, rules);
    }
}
