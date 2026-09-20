PRAGMA journal_mode=WAL;

-- A release of a distribution. Snapshots extracted on different platforms
-- (linux/windows/macos) are attached to the same release.
CREATE TABLE IF NOT EXISTS release (
    id           TEXT PRIMARY KEY,
    dist         TEXT NOT NULL,
    version      TEXT NOT NULL,
    version_major INTEGER NOT NULL,
    version_minor INTEGER NOT NULL,
    version_patch INTEGER NOT NULL,
    imported_at  TEXT NOT NULL,
    UNIQUE(dist, version)
);

-- An extractor export for one release on one platform/arch.
-- Content-addressable: identical bytes collapse to a single snapshot.
CREATE TABLE IF NOT EXISTS snapshot (
    id                TEXT PRIMARY KEY,
    release_id        TEXT NOT NULL REFERENCES release(id),
    platform          TEXT NOT NULL,
    arch              TEXT NOT NULL,
    extractor         TEXT NOT NULL,
    extractor_version TEXT NOT NULL,
    content_hash      TEXT NOT NULL UNIQUE,
    raw_json          TEXT NOT NULL,
    created_at        TEXT NOT NULL,
    UNIQUE(release_id, platform, arch)
);

CREATE TABLE IF NOT EXISTS component (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    snapshot_id TEXT NOT NULL REFERENCES snapshot(id) ON DELETE CASCADE,
    stable_id   TEXT NOT NULL,
    name        TEXT NOT NULL,
    kind        TEXT NOT NULL,            -- LIB | HEADER | MODULE | OBJECT
    boundary    TEXT NOT NULL,            -- PUBLIC | PRIVATE
    ordinal     INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_component_snapshot ON component(snapshot_id);

CREATE TABLE IF NOT EXISTS symbol (
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    snapshot_id        TEXT NOT NULL REFERENCES snapshot(id) ON DELETE CASCADE,
    component_stable_id TEXT NOT NULL,
    stable_id          TEXT NOT NULL,    -- explicit cross-platform identity
    name               TEXT NOT NULL,
    kind               TEXT NOT NULL,    -- FUNCTION | VARIABLE | TYPE_RECORD | TYPE_ENUM | TYPE_ZERO | TYPEDEF
    boundary           TEXT NOT NULL,    -- PUBLIC | PRIVATE
    visibility         TEXT NOT NULL,    -- DEFAULT | HIDDEN | PROTECTED | EXPORT | STATIC_LOCAL
    binding            TEXT,             -- GLOBAL | LOCAL | WEAK
    calling_convention TEXT,             -- C | STDCALL | CDECL | FASTCALL | AAPCS | SYSV ... null for data
    variadic           INTEGER NOT NULL DEFAULT 0,
    size               INTEGER,
    align              INTEGER,
    zero_sized         INTEGER NOT NULL DEFAULT 0,
    detail_json        TEXT NOT NULL,    -- params / fields / enum values / type layout
    ordinal            INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_symbol_snapshot ON symbol(snapshot_id);
CREATE INDEX IF NOT EXISTS idx_symbol_stable ON symbol(stable_id);

-- Aliases carrying the platform-specific name(s) of one stable symbol.
CREATE TABLE IF NOT EXISTS symbol_alias (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    snapshot_id TEXT NOT NULL REFERENCES snapshot(id) ON DELETE CASCADE,
    stable_id   TEXT NOT NULL,
    alias       TEXT NOT NULL,
    alias_kind  TEXT NOT NULL            -- EXPLICIT | WEAK_ALIAS | PLATFORM_NAME | SONAME_LINK
);
CREATE INDEX IF NOT EXISTS idx_alias_snapshot ON symbol_alias(snapshot_id);

-- Directed edges of the symbol reference graph.
CREATE TABLE IF NOT EXISTS symbol_ref (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    snapshot_id TEXT NOT NULL REFERENCES snapshot(id) ON DELETE CASCADE,
    src_stable  TEXT NOT NULL,
    dst_stable  TEXT NOT NULL,
    ref_kind    TEXT NOT NULL            -- CALL | PARAM | RETURN | FIELD | DERIVES | REEXPORT
);
CREATE INDEX IF NOT EXISTS idx_ref_src ON symbol_ref(snapshot_id, src_stable);
CREATE INDEX IF NOT EXISTS idx_ref_dst ON symbol_ref(snapshot_id, dst_stable);

-- Platform/arch-scoped, revisioned rule sets. Old revisions stay around forever
-- so historical comparisons remain reproducible.
CREATE TABLE IF NOT EXISTS ruleset (
    id         TEXT PRIMARY KEY,
    platform   TEXT NOT NULL,
    arch       TEXT NOT NULL,
    revision   INTEGER NOT NULL,
    is_active  INTEGER NOT NULL,
    note       TEXT,
    rules_json TEXT NOT NULL,            -- change-kind -> severity per boundary
    created_at TEXT NOT NULL,
    UNIQUE(platform, arch, revision)
);

-- An atomic comparison between two snapshots evaluated under one ruleset.
CREATE TABLE IF NOT EXISTS comparison (
    id                 TEXT PRIMARY KEY,
    left_snapshot_id   TEXT NOT NULL REFERENCES snapshot(id),
    right_snapshot_id  TEXT NOT NULL REFERENCES snapshot(id),
    platform           TEXT NOT NULL,
    arch               TEXT NOT NULL,
    ruleset_id         TEXT NOT NULL REFERENCES ruleset(id),
    left_release       TEXT NOT NULL,
    right_release      TEXT NOT NULL,
    input_hash         TEXT NOT NULL UNIQUE, -- dedupe + identifies exact evaluation
    created_at         TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS finding (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    comparison_id TEXT NOT NULL REFERENCES comparison(id) ON DELETE CASCADE,
    component_stable_id TEXT,
    symbol_stable_id TEXT,
    kind        TEXT NOT NULL,          -- SYMBOL_ADDED ... LAYOUT_* , CC_VARARGS ...
    category    TEXT NOT NULL,          -- ADDED | REMOVED | RENAMED | LAYOUT | SIGNATURE | VISIBILITY | GRAPH
    severity    TEXT NOT NULL,          -- BREAKING | COMPATIBLE | INFO | ALLOWED
    boundary    TEXT NOT NULL,
    title       TEXT NOT NULL,
    detail_json TEXT NOT NULL,
    affected_roots_json TEXT NOT NULL,   -- public entry points reachable via refs
    ordinal     INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_finding_comparison ON finding(comparison_id);
