package com.example.abidiff.snapshot;

import com.example.abidiff.decision.DecisionService;
import com.example.abidiff.rules.DefaultRuleSets;
import com.example.abidiff.rules.RuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Seeds built-in rule sets, fixture snapshots (content-hash deduped, safe to
 * re-run) and one intentionally expired exception fixture.
 */
@Component
public class FixtureSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FixtureSeeder.class);

    private static final Map<String, String[]> FILES = new LinkedHashMap<>();

    static {
        // dist = libwidget; file name encodes platform/arch/version
        FILES.put("fixtures/linux-x86_64-1.0.0.json", new String[]{"libwidget", "1.0.0"});
        FILES.put("fixtures/linux-x86_64-1.1.0.json", new String[]{"libwidget", "1.1.0"});
        FILES.put("fixtures/windows-x86_64-1.0.0.json", new String[]{"libwidget", "1.0.0"});
        FILES.put("fixtures/windows-x86_64-1.1.0.json", new String[]{"libwidget", "1.1.0"});
        FILES.put("fixtures/macos-aarch64-1.0.0.json", new String[]{"libwidget", "1.0.0"});
        FILES.put("fixtures/macos-aarch64-1.1.0.json", new String[]{"libwidget", "1.1.0"});
    }

    private final RuleRepository rules;
    private final SnapshotRepository snapshots;
    private final boolean seed;

    public FixtureSeeder(RuleRepository rules, SnapshotRepository snapshots,
                         @Value("${abidiff.seed-fixtures:true}") boolean seed) {
        this.rules = rules;
        this.snapshots = snapshots;
        this.seed = seed;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        DefaultRuleSets.all().values().forEach(rules::insertIfAbsent);
        if (!seed) {
            return;
        }
        var resolver = new PathMatchingResourcePatternResolver();
        for (Map.Entry<String, String[]> e : FILES.entrySet()) {
            Resource res = resolver.getResource("classpath:" + e.getKey());
            if (!res.exists()) {
                continue;
            }
            String json = StreamUtils.copyToString(res.getInputStream(), StandardCharsets.UTF_8);
            try {
                snapshots.importSnapshot(e.getValue()[0], e.getValue()[1], json);
            } catch (Exception ex) {
                log.warn("fixture import skipped for {}: {}", e.getKey(), ex.getMessage());
            }
        }
    }
}
