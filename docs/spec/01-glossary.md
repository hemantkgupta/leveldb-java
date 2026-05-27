# 01. Glossary

Every domain term used in this codebase, defined once. When a term is ambiguous in LSM literature, the definition here is the one that holds in this repo.

## Keys, values, mutations

**Slice** — `(byte[] backing, int offset, int length)` view over a byte array. Read-only by contract; the backing array is not defensively copied. Equality compares the windowed bytes. Class: `leveldb-common/.../Slice.java`.

**Key** — User-supplied lookup key. A `Slice` wrapped with stricter intent. Class: `leveldb-common/.../Key.java`. Compared lexicographically as unsigned bytes (LevelDB does not support custom comparators in this port).

**ValueType** — One-byte tag distinguishing a live value (`0x01 = Value`) from a tombstone (`0x00 = Deletion`). Class: `leveldb-common/.../ValueType.java`.

**SequenceNumber** — 56-bit monotonic counter assigned at WAL-append time. Together with `ValueType`, packs into the `InternalKey` 8-byte trailer as `(seq << 8) | type`. Class: `leveldb-common/.../SequenceNumber.java`. Max value `MAX_SEQUENCE_NUMBER = 2^56 - 1`.

**InternalKey** — `(userKey, sequence, type)` triple. The unit the engine actually stores, sorts on, and searches. Ordering: `(userKey ASC, sequence DESC, type DESC)`. The DESC tag tie-break is load-bearing — see [03 §read path](./03-engine-semantics.md#read-path). Class: `leveldb-common/.../InternalKey.java`.

**MutationRecord** — Sealed interface with two variants: `Put(key, value, sequence)` and `Delete(key, sequence)`. The WAL record payload type. Class: `leveldb-common/.../MutationRecord.java`.

**KeyLookup** — Sealed interface returned by every layer's lookup method. Three variants: `Found(value)`, `Tombstoned()`, `NotFound()`. A `Tombstoned` short-circuits descent through lower layers. Class: `leveldb-common/.../KeyLookup.java`.

**Snapshot** — Handle holding a sequence number. A read with a snapshot probes for the latest `InternalKey` whose sequence ≤ the snapshot's. Class: `leveldb-common/.../Snapshot.java`. Tracked on the engine in `activeSnapshotSeqs` and consulted by the compactor's `oldestLiveSnapshotSequence`.

## Storage layers

**MemTable** — In-memory sorted map of `InternalKey → value`. Concurrent skip-list backed by `ConcurrentSkipListMap`. Mutable until frozen, then immutable until flushed. Class: `leveldb-memtable/.../SkipListMemTable.java`.

**Active MemTable** — The MemTable accepting writes right now. `LevelDB.activeMemTable` (volatile reference).

**Frozen MemTable** — A MemTable that has been swapped out for flushing but whose SSTable is not yet on disk. Reads still consult it. `LevelDB.frozenMemTable` (volatile reference).

**WAL (Write-Ahead Log)** — Append-only log of `MutationRecord`s. One file per MemTable generation (`NNNNNN.log`). Synchronously fsynced per record by default. Used to recover MemTable contents after a crash. Classes: `leveldb-wal/.../LogWriter.java`, `LogReader.java`.

**SSTable** — Immutable sorted-string table written when a MemTable is flushed or compaction merges existing tables. File pattern `NNNNNN.ldb`. Block-based layout — see [02 §F](./02-on-disk-format.md#f-sstable-file-layout). Classes: `leveldb-sstable/.../BlockBasedTableWriter.java`, `BlockBasedTableReader.java`.

**Block** — A keyed, key-prefix-compressed run of entries inside an SSTable. Four roles: data blocks (the entries), the filter block (bloom bits), the meta-index block (pointer to the filter), the index block (pointer to each data block). All four use the same physical format. Class: `leveldb-sstable/.../Block.java`.

**BlockHandle** — `(offset, length)` pointer into an SSTable file, varlong-encoded. Length is the body length, not including the 5-byte block trailer. Class: `leveldb-sstable/.../BlockHandle.java`.

**Footer** — Fixed 48-byte trailer at the end of every SSTable. Holds the meta-index handle, the index handle, and the 8-byte magic `0xDB4775248B80FB57`. Class: `leveldb-sstable/.../Footer.java`.

**Bloom filter** — Per-SSTable probabilistic membership filter. 10 bits per key (`Constants.BLOOM_BITS_PER_KEY`), ~1% FPP. FNV-1a-based double hash (deviation from LevelDB C++ Murmur trick). Class: `leveldb-bloom/.../BloomFilter.java`; on-disk wrapper: `leveldb-sstable/.../BloomBlock.java`.

## Catalog

**FileNumber** — Monotonic 64-bit id assigned to every on-disk file (SSTable, WAL, MANIFEST). Source of `NNNNNN.ldb` / `NNNNNN.log` / `MANIFEST-NNNNNN` filenames. Class: `leveldb-common/.../FileNumber.java`.

**FileMetadata** — Catalog entry for an SSTable: `(fileNumber, sizeBytes, smallestInternalKey, largestInternalKey)`. Class: `leveldb-manifest/.../FileMetadata.java`.

**Version** — Immutable snapshot of the catalog at a point in time: per-level lists of `FileMetadata`, plus `logNumber`, `nextFileNumber`, `lastSequence`. Readers consult one Version per probe (no locks). Class: `leveldb-manifest/.../Version.java`.

**VersionEdit** — Atomic mutation to a Version. Sealed interface with five variants: `NewFile`, `DeleteFile`, `SetLogNumber`, `SetNextFileNumber`, `SetLastSequence`. Class: `leveldb-manifest/.../VersionEdit.java`. Wire format in [02 §G](./02-on-disk-format.md#g-manifest-record-payload--versionedit).

**VersionSet** — Live container holding the current Version, the MANIFEST log writer, and the `CURRENT` pointer. `apply(edits)` materializes a new Version and durably records the edits. Class: `leveldb-manifest/.../VersionSet.java`.

**MANIFEST** — Append-only log of `VersionEdit` lists, framed identically to the WAL. File pattern `MANIFEST-NNNNNN`. Class: `leveldb-manifest/.../Manifest.java`.

**CURRENT** — One-line text file in the DB directory holding the name of the active MANIFEST. The single bootstrap pointer used by `VersionSet.open`.

## Compaction

**Compaction** — Process of merging a set of SSTables at one level into a new set at the next level, dropping shadowed versions. Single-threaded and foreground in this repo.

**CompactionJob** — Plan returned by the picker: `(inputLevel, inputs, outputLevel, overlapping)`. Class: `leveldb-compaction/.../CompactionJob.java`.

**LeveledCompactionPicker** — Decides what to compact next. L0 by file count, L1+ by total byte size against `LEVEL_SIZE_BASE_BYTES × LEVEL_SIZE_MULTIPLIER^(level-1)`. Class: `leveldb-compaction/.../LeveledCompactionPicker.java`.

**MergingIterator** — N-way merge over sorted SSTable readers' entry iterators. Used by the compactor. Class: `leveldb-compaction/.../MergingIterator.java`.

**Compactor** — Executes a `CompactionJob`: opens a `MergingIterator` over inputs + overlapping, drops versions older than the snapshot horizon, writes one or more output SSTables (rotating at `Constants.SST_FILE_TARGET_SIZE_BYTES`). Class: `leveldb-compaction/.../Compactor.java`.

**Snapshot horizon** — `oldestLiveSnapshotSequence()` — the oldest sequence number that any open snapshot still requires. Versions older than this and shadowed by a newer one can be dropped during compaction. Source: `LevelDB.java:353`.

## Caching + concurrency

**BlockCache** — Shared LRU cache of decompressed data blocks. One instance per engine (no cross-engine sharing). Class: `leveldb-block-cache/.../LruBlockCache.java`; interface: `BlockCache.java`.

**CacheKey** — `(fileNumber, offset)` pair identifying a cached block. Class: `leveldb-block-cache/.../CacheKey.java`.

**writeLock** — The single `Object` monitor on `LevelDB` that serialises every `put` / `delete` / `flush` / `maybeCompact` / `close`. There is no read lock — readers traverse volatile / immutable state.

## Operator tools

**DbVerify** — Reads MANIFEST + every referenced SSTable, walks every block, recomputes block CRCs. Surfaces per-file PASS/FAIL. Class: `leveldb-tools/.../DbVerify.java`.

**DbDump** — Hex dump of every internal entry across every referenced SSTable. Class: `leveldb-tools/.../DbDump.java`.

**leveldb-cli** — Thin command-line wrapper exposing `put`, `get`, `delete`, `scan`, `verify`, `dump`, `compact`. Class: `leveldb-cli/.../LevelDbCli.java`. See [04 §CLI](./04-api-and-cli.md#cli).

## Plan / process

**Phase** — One of three high-level milestones (Foundation, LSM machinery, Polish). All three are complete.

**CP (checkpoint)** — Numbered subdivision of a Phase (CP 1 through CP 12). Each CP is one commit. Commit subject pattern: `leveldb-java: Phase N CP M: <subject> (<affected modules>)`.

**ADR** — Architectural Decision Record. Markdown file in `docs/adr/` numbered `NNNN-<slug>.md`. Template at `docs/adr/0000-template.md`. Cite from Javadoc when a non-obvious design choice diverges from LevelDB C++.
