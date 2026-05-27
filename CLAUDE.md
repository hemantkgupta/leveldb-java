# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

A faithful Java 17 port of LevelDB as it shipped circa 2011 — pure Java, no JNI. Single namespace `com.hkg.leveldb.*`. The on-disk format is intended to be forward-compatible with a future RocksDB-Java sibling repo.

The implementation is organised around an external implementation plan with three **Phases** divided into 12 **CPs** (checkpoints). Commit messages and Javadoc routinely cite them (e.g. "Phase 3 CP 11", "lands in CP 10"). Match that style when extending the engine; check the referenced CP in `README.md` for the current status table.

## Build & test

```bash
./gradlew build                                          # full build + all tests
./gradlew :leveldb-engine:test                           # tests for a single module
./gradlew :leveldb-engine:test --tests LevelDBReadPathTest          # single test class
./gradlew :leveldb-engine:test --tests LevelDBReadPathTest.someMethod  # single test method
./gradlew :leveldb-cli:run --args="verify /path/to/db"   # not wired; use the jar or main directly
```

Java 17 is required (`.java-version` pins `17.0`; use `jenv local 17.0` or equivalent). There is no separate lint step — `./gradlew build` is the gate.

Long-running stress test:

```bash
./gradlew :leveldb-test-cluster:test -Dleveldb.stress.duration.seconds=600
```

(Default duration is 5 s so it stays in CI budget; bump it for real soak testing. Seed is overridable via `-Dleveldb.stress.seed=...`.)

## Module layout

Twelve Gradle subprojects, all configured from the root `build.gradle` (per-module `build.gradle` files are intentionally empty). Dependency wiring lives in the root `build.gradle` and is mirrored in `docs/architecture.md` — keep them in sync if you add edges.

```
leveldb-common         Slice, Key, InternalKey, MutationRecord, SequenceNumber,
                       KvEngine (public surface), Constants (compile-time knobs)
leveldb-memtable       SkipListMemTable (concurrent skip-list, freeze semantics)
leveldb-wal            LogWriter/LogReader, MutationCodec, 32 KiB record framing
leveldb-bloom          Per-SSTable bloom filter (10 bits/key)
leveldb-sstable        Block-based SSTable v1: data/index/filter/footer,
                       BlockBuilder, BlockBasedTableReader/Writer, VarInt,
                       Compression (JDK Deflater, not Snappy — see ADR-0008)
leveldb-manifest       VersionEdit, Version, VersionSet, FileMetadata, codec
leveldb-compaction     LeveledCompactionPicker, MergingIterator, Compactor,
                       CompactionJob (single-threaded leveled compaction, K=10)
leveldb-block-cache    LruBlockCache + BlockCache + CacheKey (shared, per-engine)
leveldb-engine         LevelDB.java — assembles everything; the only place that
                       composes WAL + MemTable + SSTable + MANIFEST + cache
leveldb-tools          DbVerify (block-CRC walk), DbDump (hex dump)
leveldb-test-cluster   Integration fixture: StressTest + CrashRecoveryTest
leveldb-cli            LevelDbCli: put/get/delete/scan/verify/dump/compact
```

The graph is acyclic. Storage modules (memtable/wal/sstable/bloom) never depend on each other; composition happens in `leveldb-engine`. `leveldb-compaction` is the one exception — it pulls in `sstable` + `manifest` because the algorithm spans both.

## Engine architecture (read this before touching `leveldb-engine`)

`LevelDB.java` is the integration seam. The high-level layering:

1. **Writes** serialise on `writeLock`. Each write allocates a new sequence number, appends to the WAL (fsync per record by default), and inserts into the active `SkipListMemTable`. `maybeFlush()` triggers an L0 flush at 4 MiB.
2. **Flush** freezes the active MemTable into `frozenMemTable`, opens a fresh WAL, writes the frozen contents as a new L0 SSTable, applies a `NewFile` VersionEdit, opens a reader (wired into the shared `BlockCache`), then deletes the old WAL. Readers see flush atomically via the volatile `frozenMemTable` reference.
3. **Reads** (`readAt`) probe in strict order: active MemTable → frozen MemTable → L0 (newest file first) → L1..L_max (binary search per level). Each probe returns `KeyLookup.Found`, `KeyLookup.Tombstoned`, or `KeyLookup.NotFound`; a `Tombstoned` short-circuits the descent.
4. **Compactions** are foreground (`maybeCompact()` is manual; no background thread). The picker proposes a `CompactionJob`; `Compactor.run` produces output SSTables; the engine applies `DeleteFile`/`NewFile` VersionEdits atomically, then closes + deletes inputs.
5. **Crash recovery**: on `open()`, the engine replays the prior WAL into a fresh MemTable, then immediately flushes it as L0 so the old WAL can be deleted. `closeWithoutFlush()` simulates a crash in tests — every acked write is durable because the WAL is fsynced per record.
6. **Snapshots** are sequence-number handles tracked in `activeSnapshotSeqs`. The compactor consults `oldestLiveSnapshotSequence()` to decide whether old versions can be dropped.

### Internal key ordering (load-bearing)

`InternalKey.compareTo` is `(userKey ASC, sequence DESC, type DESC)`. The DESC tag tie-break is deliberate: a snapshot lookup probes `(userKey, S, VALUE)`, and with DESC tag ordering a same-sequence tombstone sorts AFTER the probe, so `ceilingEntry` returns the tombstone if one exists. Don't "fix" this comparator without understanding the read path.

### Filename conventions

`FileNumber.value()` is the monotonic id; helpers produce `NNNNNN.ldb` (SSTable), `NNNNNN.log` (WAL), `MANIFEST-NNNNNN`. `LevelDB.open` sweeps `.ldb.tmp` and orphan `.ldb` files not referenced by the recovered Version — don't add stray files into `dbDir`.

## Conventions to follow

- **No runtime configurability.** All knobs live in `leveldb-common`'s `Constants`. Adding a new tunable means a new `Constants` field, not a config object or options struct — this mirrors the LevelDB original and keeps the engine fits-in-your-head.
- **Sealed interfaces + records** are the default for fixed-variant types (`MutationRecord`, `KeyLookup`, `VersionEdit`). Switch over them as exhaustive switch expressions; the compiler will catch missing arms when a new variant is added.
- **`Slice` is read-only by contract.** The backing array is not defensively copied; callers must not mutate after handing one in. Mirrors LevelDB C++.
- **Javadoc carries the design rationale.** Where invariants are non-obvious (the `InternalKey` comparator, the `LevelDB.open` recovery dance, the compactor's snapshot horizon), classes have multi-paragraph Javadoc. Keep that habit when adding subtle code; don't strip those comments.
- **Testing**: JUnit 5 + AssertJ. Mirror the `src/main/java` package layout in `src/test/java`. Use `@TempDir` for engine tests so artifacts don't leak between runs.

## ADRs

Architecture decisions live in `docs/adr/` and are referenced by README and code (e.g. ADR-0008 explains why Deflater instead of Snappy). When making a non-obvious design choice — especially one that diverges from LevelDB C++ — copy `0000-template.md`, increment the number, and link it from the relevant Javadoc.

## Git workflow

Commit messages follow the pattern `leveldb-java: Phase N CP M: <subject> (<affected-modules>)`. Single linear history; CPs are committed in order and never rewritten. Match this style for new CP work.
