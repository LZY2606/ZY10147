PRAGMA journal_mode=WAL;

-- Reviewer decisions (exceptions or confirmations), kept strictly separate from
-- the immutable snapshot store. One row per (comparison, symbol, platform).
-- Two platforms may carry different conclusions: insert one row per platform
-- scope, or a single '*' row that applies to every platform.
CREATE TABLE IF NOT EXISTS resolution (
    id                 TEXT PRIMARY KEY,
    comparison_id      TEXT NOT NULL,
    symbol_stable_id   TEXT NOT NULL,
    platform           TEXT NOT NULL,          -- '*' or 'linux'|'windows'|'macos'
    decision           TEXT NOT NULL,          -- EXCEPTION | ACCEPT_BREAK | REJECT_RELEASE
    reason             TEXT,
    -- Exception validity envelope.
    scope_from_version TEXT,                   -- inclusive semver lower bound
    scope_to_version   TEXT,                   -- inclusive semver upper bound
    expires_at_version TEXT,                   -- final version where the exception is valid
    -- Lock to the exact ruleset revision the reviewer approved against.
    -- A rule revision bump does NOT extend an old exception: new comparisons
    -- reference a new ruleset and the old resolution fails to apply.
    ruleset_id         TEXT NOT NULL,
    base_comparison_rev INTEGER NOT NULL,      -- optimistic-concurrency base
    created_by         TEXT NOT NULL,
    created_at         TEXT NOT NULL,
    expired_at         TEXT,                   -- set when a reviewer expires it early
    UNIQUE(comparison_id, symbol_stable_id, platform)
);
CREATE INDEX IF NOT EXISTS idx_resolution_comparison ON resolution(comparison_id);
CREATE INDEX IF NOT EXISTS idx_resolution_symbol ON resolution(symbol_stable_id);

-- Append-only audit trail of every decision submission (conflicts included).
CREATE TABLE IF NOT EXISTS resolution_event (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    resolution_id TEXT,
    comparison_id TEXT NOT NULL,
    symbol_stable_id TEXT,
    platform      TEXT,
    payload_json  TEXT NOT NULL,
    result        TEXT NOT NULL,              -- APPLIED | SUPERSEDED | CONFLICT | EXPIRED_EARLY
    actor         TEXT NOT NULL,
    at            TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_event_comparison ON resolution_event(comparison_id);

-- Monotonic per-comparison revision used for optimistic concurrency.
CREATE TABLE IF NOT EXISTS comparison_rev (
    comparison_id TEXT PRIMARY KEY,
    rev           INTEGER NOT NULL
);
