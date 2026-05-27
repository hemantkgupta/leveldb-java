# 00. Overview

## What this repo is

A faithful Java 17 port of [LevelDB](https://github.com/google/leveldb) as it shipped circa 2011 — the embedded LSM-tree storage engine open-sourced by Ghemawat & Dean (Google), distilled out of Bigtable's storage layer. Pure Java, no JNI. Single root namespace `com.hkg.leveldb.*`. Twelve-module Gradle build.

The implementation tracks a three-phase, twelve-checkpoint (CP) external plan. Commit messages, Javadoc, and this spec routinely cite phase/CP numbers. The current state (`README.md`):

| Phase | Status | What it lights up |
|---|---|---|
| 1 — Foundation | complete (CP 1-4) | `common`, `memtable`, `wal`, `bloom` |
| 2 — LSM machinery | complete (CP 5-9) | `sstable`, `manifest`, `compaction`, engine read path, snapshot/recovery |
| 3 — Polish + verification | complete (CP 10-12) | `block-cache`, `tools`, `cli`, integration stress test |

181 tests across 12 modules; shared 8 MiB LRU block cache; `DbVerify` / `DbDump` / `leveldb-cli` for offline inspection; randomised crash-and-recover stress test.

## In scope

- Single-process, single-engine embedded LSM.
- `put` / `get` / `delete` over byte-array keys and values.
- Snapshot reads at a point-in-time sequence number.
- Synchronous WAL (fsync per record by default — `Constants.WAL_SYNC_DEFAULT`).
- Block-based SSTable v1 with key-prefix compression, per-block CRC, optional Deflate compression, per-SSTable bloom filter, shared block cache.
- Leveled compaction (K=10) with seven levels.
- MANIFEST + `VersionEdit` recovery, orphan sweep on open.
- Crash recovery via WAL replay → immediate L0 flush.
- CLI: `put` / `get` / `delete` / `scan` / `verify` / `dump` / `compact`.

## Deferred (named gaps to be aware of)

These are *deliberately* out of scope for the leveldb-java repo. They are documented as deferred so an LLM modifying this code does not "fix" them by accident.

| Gap | Where | Pointer |
|---|---|---|
| `scan(from, to)` — forward iterator | `KvEngine.scan` throws `UnsupportedOperationException` | `LevelDB.java:515` notes "lands in CP 10 (Phase 3)" but was descoped; current CLI `scan` falls back to `DbDump`. |
| Background compaction thread | `LevelDB.maybeCompact()` is manual; no background driver | Foreground only by design (Phase 2 CP 7 scope). |
| Tiered / Dynamic Leveled compaction | n/a | RocksDB-bridge work, not LevelDB. |
| Column families | n/a | RocksDB-bridge work. |
| Per-host RateLimiter | n/a | RocksDB-bridge work. |
| L0 slowdown / stop triggers | constants defined; not enforced in writes | `Constants.L0_SLOWDOWN_WRITES_TRIGGER`, `L0_STOP_WRITES_TRIGGER` exist but `put`/`delete` do not consult them. |
| MANIFEST rotation at threshold | `Constants.MANIFEST_ROTATION_BYTES` defined | `VersionSet` does not currently rotate; deferred. |
| Snappy compression | `Compression` only knows `none` and `deflate` | ADR-0008 records the choice; native Snappy intentionally avoided. |
| Native byte-compatibility with LevelDB C++ on disk | bloom block layout, CRC mask, Snappy | See [02 §"Deferred / out of scope"](./02-on-disk-format.md#deferred--out-of-scope-current-state). |
| User-defined comparator | `InternalKey` uses unsigned byte order only | LevelDB C++ supports custom `Comparator`; not ported. |
| LevelDB "Get + L0 seek penalty" heuristic for compaction selection | n/a | Picker uses size + L0 count only. |

## How to read this spec

Read in order if you are new. Jump straight to the file matching what you want to change:

| If you want to … | Read |
|---|---|
| Understand the project's vocabulary | [01-glossary.md](./01-glossary.md) |
| Change a byte layout, add a codec, debug a parse error | [02-on-disk-format.md](./02-on-disk-format.md) |
| Change read/write/flush/compact ordering, snapshot semantics, recovery | [03-engine-semantics.md](./03-engine-semantics.md) |
| Add a public API method, change CLI behavior, add an operator tool | [04-api-and-cli.md](./04-api-and-cli.md) |
| Navigate the modules; look up a class's location | [05-module-reference.md](./05-module-reference.md) |
| Add a new VersionEdit / SSTable feature / module, file an ADR, name a commit | [06-extension-guide.md](./06-extension-guide.md) |

Cross-reference everything by `file:line`. The Javadoc on every public class is the second source of truth — when prose in this spec drifts from Javadoc, Javadoc wins and this spec is wrong.

## Reference targets

- **Original**: [`google/leveldb`](https://github.com/google/leveldb) at ~2011.
- **Sister port (future)**: RocksDB-Java will layer on top of these primitives; the on-disk format is intended forward-compatible *modulo* the deviations listed in [02 §"Deferred / out of scope"](./02-on-disk-format.md#deferred--out-of-scope-current-state).
- **Implementation plan**: external, referenced from `README.md`.
- **Decision records**: `docs/adr/`.

## Non-goals (explicit)

- Performance parity with LevelDB C++. The port chooses pedagogical clarity over micro-optimisation.
- Backward compatibility within this codebase before Phase 3 is complete. CPs land breaking changes when they need to.
- Multi-threaded write throughput. `writeLock` serialises every mutation.
- Tooling around backup, replication, or remote access. This is an embedded engine.
