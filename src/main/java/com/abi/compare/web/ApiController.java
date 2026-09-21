package com.abi.compare.web;

import com.abi.compare.diff.DiffResult;
import com.abi.compare.model.Decision;
import com.abi.compare.model.Snapshot;
import com.abi.compare.rule.RuleRegistry;
import com.abi.compare.rule.RuleSet;
import com.abi.compare.storage.ComparisonService;
import com.abi.compare.storage.SnapshotService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final SnapshotService snapshotService;
    private final ComparisonService comparisonService;
    private final RuleRegistry ruleRegistry;

    public ApiController(SnapshotService snapshotService, ComparisonService comparisonService,
                         RuleRegistry ruleRegistry) {
        this.snapshotService = snapshotService;
        this.comparisonService = comparisonService;
        this.ruleRegistry = ruleRegistry;
    }

    @GetMapping("/rules")
    public List<RuleSet> rules() {
        return new ArrayList<>(ruleRegistry.all().values());
    }

    @GetMapping("/snapshots")
    public List<Map<String, Object>> snapshots() {
        return snapshotService.list();
    }

    @PostMapping("/snapshots")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> upload(@RequestBody Snapshot snapshot) {
        return snapshotService.ingest(snapshot);
    }

    @GetMapping("/snapshots/{hash}")
    public Snapshot snapshot(@PathVariable String hash) {
        return snapshotService.load(hash);
    }

    @GetMapping("/comparisons")
    public List<Map<String, Object>> comparisons() {
        return comparisonService.list();
    }

    @PostMapping("/comparisons")
    @ResponseStatus(HttpStatus.CREATED)
    public DiffResult compare(@RequestBody Map<String, String> request) {
        return comparisonService.compare(
                request.get("oldSnapshotHash"),
                request.get("newSnapshotHash"),
                request.getOrDefault("ruleSetId", "linux-aarch64-public"));
    }

    @GetMapping("/comparisons/{id}")
    public DiffResult comparison(@PathVariable String id) {
        return comparisonService.get(id);
    }

    @PostMapping("/comparisons/{id}/decisions")
    @ResponseStatus(HttpStatus.CREATED)
    public Object decide(@PathVariable String id, @RequestBody Decision decision) {
        decision.comparisonId = id;
        return comparisonService.decide(id, decision);
    }
}
