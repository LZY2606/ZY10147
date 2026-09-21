package com.abi.compare.storage;

import com.abi.compare.diff.JsonHasher;
import com.abi.compare.model.Snapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SnapshotService {

    private final Repository repo;
    private final ObjectMapper mapper;

    public SnapshotService(Repository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    /** Validates, content-hashes and de-duplicates one uploaded snapshot. */
    public Map<String, Object> ingest(Snapshot snapshot) {
        validate(snapshot);
        String hash = JsonHasher.sha256(snapshot);
        snapshot.contentHash = hash;
        boolean alreadyPresent = repo.findSnapshot(hash).isPresent();
        String payload;
        try {
            payload = mapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            throw new IllegalArgumentException("cannot serialize snapshot", e);
        }
        repo.insertSnapshotIgnore(hash, snapshot.component, snapshot.release,
                snapshot.platform, snapshot.arch,
                snapshot.extractor == null ? null : snapshot.extractor.name,
                snapshot.extractor == null ? null : snapshot.extractor.version,
                payload, Instant.now().toString());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("contentHash", hash);
        out.put("deduped", alreadyPresent);
        return out;
    }

    public Snapshot load(String hash) {
        Map<String, Object> row = repo.findSnapshot(hash)
                .orElseThrow(() -> new IllegalArgumentException("unknown snapshot " + hash));
        try {
            Snapshot snapshot = mapper.readValue((String) row.get("payload"), Snapshot.class);
            snapshot.contentHash = hash;
            return snapshot;
        } catch (Exception e) {
            throw new IllegalStateException("corrupt snapshot payload for " + hash, e);
        }
    }

    public List<Map<String, Object>> list() {
        return repo.listSnapshots();
    }

    private void validate(Snapshot snapshot) {
        require(snapshot.component, "component");
        require(snapshot.release, "release");
        require(snapshot.platform, "platform");
        require(snapshot.arch, "arch");
    }

    private void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("snapshot field required: " + field);
        }
    }
}
