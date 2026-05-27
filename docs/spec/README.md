# leveldb-java specification

This is the canonical reference for what leveldb-java does and how it does it. The split is deliberate — each file is read independently, and an LLM only needs the relevant one(s) to make a change.

## Files in this directory

| # | File | Read this when… |
|---|---|---|
| 00 | [overview.md](./00-overview.md) | You are new to the repo and need the 5-minute orientation: what's in, what's out, where the deferred gaps live. |
| 01 | [glossary.md](./01-glossary.md) | You hit a term you don't recognise. Every domain term defined once, with a pointer to its canonical class. |
| 02 | [on-disk-format.md](./02-on-disk-format.md) | You are changing or debugging a byte layout: WAL framing, SSTable blocks, MANIFEST records, footer, varint, internal-key encoding. The byte tables are the spec. |
| 03 | [engine-semantics.md](./03-engine-semantics.md) | You are touching the read path, write path, flush, compaction, snapshot, recovery, or concurrency model. Mermaid sequence/state/flow diagrams included. |
| 04 | [api-and-cli.md](./04-api-and-cli.md) | You are adding a public API method, a CLI subcommand, or an operator tool — or wiring against `KvEngine` from outside. |
| 05 | [module-reference.md](./05-module-reference.md) | You need to navigate the modules: which class is where, which module owns which concern, plus the full `Constants` table. |
| 06 | [extension-guide.md](./06-extension-guide.md) | You are adding a new VersionEdit / SSTable feature / CLI subcommand / module / Constants field. Recipes + conventions + ADR process. |

## "Where do I look to change X?" — common edits

| You want to change… | Start in |
|---|---|
| The WAL fragment header layout | [02 §A](./02-on-disk-format.md#a-wal-physical-framing-also-used-by-manifest) |
| What gets written to the WAL per mutation | [02 §B](./02-on-disk-format.md#b-wal-record-payload--mutationrecord) |
| The SSTable footer or magic number | [02 §F.5](./02-on-disk-format.md#f5-footer--last-48-bytes-of-every-ldb-file) |
| Compression algorithm or framing | [02 §E.2, §E.3](./02-on-disk-format.md#e2-block-trailer-5-bytes--added-by-blockbasedtablewriterwriteblock) |
| Bloom filter hash or layout | [02 §F.3](./02-on-disk-format.md#f3-filter-bloom-block) — note the deliberate deviation from LevelDB C++ |
| Add a VersionEdit variant | [06 Recipe A](./06-extension-guide.md#recipe-a--add-a-new-versionedit-variant) |
| The read-path probe order | [03 §read path](./03-engine-semantics.md#read-path) |
| The flush trigger or sequence | [03 §flush](./03-engine-semantics.md#flush) |
| Compaction picker scoring | [03 §compaction picker](./03-engine-semantics.md#compaction-picker--leveledcompactionpicker) |
| Snapshot-horizon GC rules | [03 §compactor](./03-engine-semantics.md#compactor--compactorrun) |
| Crash-recovery sequence | [03 §open + crash recovery](./03-engine-semantics.md#open--crash-recovery) |
| Concurrency / locking | [03 §concurrency model](./03-engine-semantics.md#concurrency-model) |
| Add a CLI subcommand | [06 Recipe C](./06-extension-guide.md#recipe-c--add-a-new-cli-subcommand) |
| A `Constants` value | [05 §appendix](./05-module-reference.md#appendix-constants) + [06 Recipe E](./06-extension-guide.md#recipe-e--change-a-constants-value) |
| Implement `scan()` (currently throws) | [00 §deferred](./00-overview.md#deferred-named-gaps-to-be-aware-of) + add a CP per [06 §1](./06-extension-guide.md#1-how-a-cp-is-born) |
| File a design decision (ADR) | [06 §2](./06-extension-guide.md#2-adr-process) |

## Spec maintenance contract

1. **Source of truth ranking**: code > Javadoc > this spec > ADRs > READMEs. If they disagree, fix the lower-ranked artifact.
2. **Byte layouts**: any change to a wire format requires a paired update to [02-on-disk-format.md](./02-on-disk-format.md) in the same commit.
3. **Public API or CLI changes**: paired update to [04-api-and-cli.md](./04-api-and-cli.md).
4. **New `Constants` field**: paired update to the table in [05 §appendix](./05-module-reference.md#appendix-constants).
5. **Behavioural change to engine ordering** (write path, flush, compaction, recovery, concurrency): paired update to [03-engine-semantics.md](./03-engine-semantics.md), including the Mermaid diagrams if they're affected.
6. **New module**: paired update to [05](./05-module-reference.md), [06 Recipe D](./06-extension-guide.md#recipe-d--add-a-new-module), AND `docs/architecture.md`.

Drift is the failure mode this directory is designed to prevent. If you find drift, the fix is a doc-only CP.

## Related docs in this repo

- `README.md` (root) — project pitch and phase status.
- `CLAUDE.md` (root) — quick-start for AI assistants working in this repo.
- `docs/architecture.md` — at-a-glance module dependency graph (same info as [05 §dependency graph](./05-module-reference.md), kept separately because it's referenced from external docs).
- `docs/adr/` — decision records. `0000-template.md` is the template; copy it for new ADRs.
