# Architecture — module dependency graph

```
leveldb-common (no deps)
    ↑
    ├── leveldb-memtable ────────── (depends on common)
    │
    ├── leveldb-wal ──────────────── (depends on common)
    │
    ├── leveldb-bloom ────────────── (depends on common)
    │       ↑
    ├── leveldb-sstable ─────────── (depends on common, bloom)
    │       ↑
    ├── leveldb-manifest ─────────── (depends on common)
    │       ↑
    ├── leveldb-compaction ───────── (depends on common, sstable, manifest)
    │
    ├── leveldb-block-cache ───────── (depends on common)
    │
    ├── leveldb-engine ─────────────── (depends on all of: common, memtable, wal,
    │                                   sstable, bloom, manifest, compaction,
    │                                   block-cache)
    │
    ├── leveldb-tools ─────────────── (depends on engine)
    ├── leveldb-test-cluster ──────── (depends on engine)
    └── leveldb-cli ────────────────── (depends on engine, tools)
```

The graph is acyclic. Storage modules (memtable, wal, sstable, bloom) do not depend on each other except through the explicit composition in `leveldb-engine`. `leveldb-compaction` depends on `sstable` + `manifest` because it composes both at the algorithm level.

## Phase boundaries

- **Phase 1** lights up `common`, `memtable`, `wal`, `bloom`.
- **Phase 2** lights up `sstable`, `manifest`, `compaction`, plus the read path inside `engine`.
- **Phase 3** lights up `block-cache`, `tools`, `cli`, `test-cluster`.

`leveldb-engine` is the integration module; it accumulates dependencies as phases land.
