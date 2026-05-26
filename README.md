# leveldb-java

A faithful Java 17 implementation of LevelDB as it shipped in 2011 — the minimal LSM-tree storage engine open-sourced by Ghemawat & Dean (Google), distilled out of Bigtable's storage layer.

This repo is the **gateway build** for the storage-engines paper arc. If you complete Phase 3, you have a working LevelDB-equivalent embedded LSM. RocksDB Phases 3-7 ([sibling repo, future](https://github.com/hemantkgupta/rocksdb-java)) layer on top of this code; the on-disk format is forward-compatible.

## Target

| | |
|---|---|
| Reference | [`google/leveldb`](https://github.com/google/leveldb) at ~2011 |
| Language | Java 17 (no JNI) |
| Build | Gradle multi-module |
| Test | JUnit 5 + AssertJ |
| Compression | JDK `Deflater` (pedagogical departure from native Snappy; see ADR-0008) |
| Namespace | `com.hkg.leveldb.*` |
| Implementation plan | [`CSE-Raw/raw-blog/storage-engines/leveldb-implementation-plan.md`](https://github.com/hemantkgupta/CSE-Raw/blob/main/raw-blog/storage-engines/leveldb-implementation-plan.md) |

## Module layout

```
leveldb-java/
├── leveldb-common         # Slice, InternalKey, SequenceNumber, MutationRecord, engine interface
├── leveldb-memtable       # SkipListMemTable (concurrent skip-list)
├── leveldb-wal            # Write-ahead log (synchronous, fsync per write)
├── leveldb-sstable        # Block-based SSTable v1 (data/index/filter/footer)
├── leveldb-bloom          # Per-SSTable bloom filter (10 bits/key)
├── leveldb-manifest       # MANIFEST file + VersionEdit
├── leveldb-compaction     # Single-threaded leveled compaction (K=10)
├── leveldb-block-cache    # Shared LRU block cache
├── leveldb-engine         # Assembled engine: LevelDB.put/get/scan/snapshot
├── leveldb-tools          # db_verify + db_dump CLI helpers
├── leveldb-test-cluster   # In-process integration test fixture
└── leveldb-cli            # Command-line wrapper
```

## Build

```bash
jenv local 17.0
./gradlew build
```

(Requires Java 17. If you use jenv, the included `.java-version` file pins it.)

## Phase status

| Phase | CPs | Status |
|---|---|---|
| Phase 1 — Foundation | 4 | _complete_ |
| Phase 2 — LSM machinery | 5 | _in progress (CP 5-6 done)_ |
| Phase 3 — Polish + verification | 3 | _pending_ |

After Phase 3 the engine is feature-complete vs LevelDB 2011. See [the implementation plan](https://github.com/hemantkgupta/CSE-Raw/blob/main/raw-blog/storage-engines/leveldb-implementation-plan.md) for what's in scope and what's not (deliberate stop point — multi-threaded compaction / tiered / Dynamic Leveled / column families / four-layer integrity / per-host RateLimiter / UDTs belong to the RocksDB extension).

## Why this exists

LevelDB was designed to be readable end-to-end — the entire engine fits in your head. ~25k LOC of C++ in the original. A faithful Java port lands around 35-45k LOC. Building it gives you the load-bearing LSM primitives (MemTable + WAL + SSTable + MANIFEST + leveled compaction + bloom filter + snapshot semantics + crash recovery) without RocksDB's accumulated optionality. This repo is the gateway learning unit for the storage-engines arc.

## License

MIT. The original `google/leveldb` is BSD-3-clause; nothing in this port reuses that code directly.
