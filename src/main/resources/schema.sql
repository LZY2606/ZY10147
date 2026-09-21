CREATE TABLE IF NOT EXISTS snapshots (
    content_hash TEXT PRIMARY KEY,
    component    TEXT NOT NULL,
    release      TEXT NOT NULL,
    platform     TEXT NOT NULL,
    arch         TEXT NOT NULL,
    extractor_name TEXT,
    extractor_version TEXT,
    payload      TEXT NOT NULL,
    imported_at  TEXT NOT NULL
);

-- One comparison per (snapshot pair, rule set). Row identity is stable; version
-- is bumped per decision and used for optimistic concurrency control.
CREATE TABLE IF NOT EXISTS comparisons (
    id               TEXT PRIMARY KEY,
    old_snapshot_hash TEXT NOT NULL,
    new_snapshot_hash TEXT NOT NULL,
    component        TEXT NOT NULL,
    old_release      TEXT NOT NULL,
    new_release      TEXT NOT NULL,
    platform         TEXT NOT NULL,
    arch             TEXT NOT NULL,
    rule_set_id      TEXT NOT NULL,
    rule_set_version TEXT NOT NULL,
    diff_payload     TEXT NOT NULL,
    version          INTEGER NOT NULL DEFAULT 0,
    created_at       TEXT NOT NULL,
    UNIQUE(old_snapshot_hash, new_snapshot_hash, rule_set_id)
);

-- Decisions live in their own tables, never mixed with raw snapshots.
CREATE TABLE IF NOT EXISTS decisions (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    comparison_id  TEXT NOT NULL REFERENCES comparisons(id),
    stable_id      TEXT NOT NULL,
    change_kind    TEXT,
    platform       TEXT NOT NULL DEFAULT '*',
    verdict        TEXT NOT NULL,
    rationale      TEXT,
    effective_from TEXT NOT NULL,
    expires_after_version TEXT,
    base_version   INTEGER NOT NULL,
    decided_at     TEXT NOT NULL,
    UNIQUE(comparison_id, stable_id, change_kind, platform)
);

CREATE INDEX IF NOT EXISTS idx_decisions_comparison ON decisions(comparison_id);
