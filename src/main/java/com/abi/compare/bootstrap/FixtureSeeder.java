package com.abi.compare.bootstrap;

import com.abi.compare.model.Decision;
import com.abi.compare.model.Snapshot;
import com.abi.compare.storage.ComparisonService;
import com.abi.compare.storage.SnapshotService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Loads bundled extractor exports so the demo has data on first boot.
 * Seeding is idempotent: snapshots de-duplicate by content hash and
 * comparison creation is atomic/idempotent.
 */
@Component
public class FixtureSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FixtureSeeder.class);

    private static final String[] FIXTURES = {
            "fixtures/libwidget-linux-1.4.0.json",
            "fixtures/libwidget-linux-2.0.0.json",
            "fixtures/libwidget-windows-1.4.0.json",
            "fixtures/libwidget-windows-2.0.0.json"
    };

    private final ObjectMapper mapper;
    private final SnapshotService snapshots;
    private final ComparisonService comparisons;

    public FixtureSeeder(ObjectMapper mapper, SnapshotService snapshots,
                         ComparisonService comparisons) {
        this.mapper = mapper;
        this.snapshots = snapshots;
        this.comparisons = comparisons;
    }

    @Override
    public void run(ApplicationArguments args) {
        Map<String, String> hashes = new LinkedHashMap<>();
        for (String path : FIXTURES) {
            try (InputStream in = new ClassPathResource(path).getInputStream()) {
                Snapshot snapshot = mapper.readValue(in, Snapshot.class);
                Map<String, Object> result = snapshots.ingest(snapshot);
                hashes.put(key(snapshot), (String) result.get("contentHash"));
            } catch (Exception e) {
                log.warn("could not seed fixture {}: {}", path, e.getMessage());
            }
        }

        String linuxOld = hashes.get("linux|aarch64|1.4.0");
        String linuxNew = hashes.get("linux|aarch64|2.0.0");
        String winOld = hashes.get("windows|amd64|1.4.0");
        String winNew = hashes.get("windows|amd64|2.0.0");

        create("linux v1 rules", linuxOld, linuxNew, "linux-aarch64-public");
        create("linux v2 rules", linuxOld, linuxNew, "linux-aarch64-public-v2");
        create("linux private boundary", linuxOld, linuxNew, "linux-aarch64-private");
        create("windows v1 rules", winOld, winNew, "windows-amd64-public");
        seedDemoDecisions(linuxOld, linuxNew);
    }

    private void create(String label, String oldHash, String newHash, String ruleSetId) {
        if (oldHash == null || newHash == null) {
            return;
        }
        try {
            comparisons.compare(oldHash, newHash, ruleSetId);
            log.info("seeded comparison: {}", label);
        } catch (IllegalArgumentException e) {
            log.info("skip comparison {}: {}", label, e.getMessage());
        }
    }

    private void seedDemoDecisions(String oldHash, String newHash) {
        if (oldHash == null || newHash == null) {
            return;
        }
        try {
            String id = comparisons.compare(oldHash, newHash, "linux-aarch64-public").id;

            // Expired exception: accepted the private tail append for 1.x only.
            // 2.0.0 is beyond expiresAfterVersion, so it renders EXPIRED and is
            // NOT re-extended automatically.
            Decision expired = new Decision();
            expired.stableId = "libwidget.Packet";
            expired.changeKind = "FIELD_APPENDED_PRIVATE_TAIL";
            expired.platform = "*";
            expired.verdict = "ACCEPT";
            expired.rationale = "internal consumers rebuilt for 1.x; exception never renews";
            expired.effectiveFrom = "1.4.0";
            expired.expiresAfterVersion = "1.99.99";
            expired.baseVersion = 0L;
            decideOnce(id, expired);

            // Platform-split conclusion on the alias removal: Linux team rejects
            // (no platform override means the global row covers linux); a Windows
            // row demonstrates different conclusions per platform.
            Decision winAccept = new Decision();
            winAccept.stableId = "libwidget.widget_init";
            winAccept.changeKind = "ALIAS_REMOVED";
            winAccept.platform = "windows";
            winAccept.verdict = "ACCEPT";
            winAccept.rationale = "Windows keeps the unmangled widget_init forwarder";
            winAccept.effectiveFrom = "2.0.0";
            winAccept.expiresAfterVersion = "2.9.0";
            winAccept.baseVersion = 0L;
            decideOnce(id, winAccept);
        } catch (Exception e) {
            log.info("decision seeding skipped: {}", e.getMessage());
        }
    }

    private void decideOnce(String id, Decision decision) {
        try {
            comparisons.decide(id, decision);
        } catch (RuntimeException e) {
            // Unique constraint / stale base on re-seed: already present.
            log.info("decision already present: {}", e.getMessage());
        }
    }

    private String key(Snapshot s) {
        return s.platform + "|" + s.arch + "|" + s.release;
    }
}
