package com.hkg.leveldb.testcluster;

import com.hkg.leveldb.common.Key;
import com.hkg.leveldb.common.Slice;
import com.hkg.leveldb.engine.LevelDB;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end crash-recovery scenarios. Each test opens an engine, exercises
 * some writes, simulates a crash (close without flushing, or truncate the
 * WAL file), and verifies that a fresh open recovers the state.
 */
class CrashRecoveryTest {

    @Test
    void cleanReopenSeesAllWrites(@TempDir Path dir) throws IOException {
        try (LevelDB db = LevelDB.open(dir)) {
            for (int i = 0; i < 100; i++) {
                db.put(Key.of(String.format("k-%05d", i)), Slice.of("v" + i));
            }
        }
        try (LevelDB db = LevelDB.open(dir)) {
            for (int i = 0; i < 100; i++) {
                Optional<Slice> v = db.get(Key.of(String.format("k-%05d", i)));
                assertThat(v).as("k-%05d", i).isPresent();
                assertThat(new String(v.get().toBytes())).isEqualTo("v" + i);
            }
        }
    }

    @Test
    void walReplayRecoversUnflushedMemTable(@TempDir Path dir) throws IOException {
        // Open, write a bunch, abandon without close() — simulates a crash.
        LevelDB db = LevelDB.open(dir);
        for (int i = 0; i < 100; i++) {
            db.put(Key.of("k" + i), Slice.of("v" + i));
        }
        db.closeWithoutFlush();

        try (LevelDB recovered = LevelDB.open(dir)) {
            for (int i = 0; i < 100; i++) {
                Optional<Slice> v = recovered.get(Key.of("k" + i));
                assertThat(v).as("k%d", i).isPresent();
                assertThat(new String(v.get().toBytes())).isEqualTo("v" + i);
            }
        }
    }

    @Test
    void truncatedWalTailRecoversWhatIsDurable(@TempDir Path dir) throws IOException {
        long walNum;
        LevelDB db = LevelDB.open(dir);
        for (int i = 0; i < 50; i++) {
            db.put(Key.of(String.format("k%04d", i)), Slice.of(String.format("v%04d", i)));
        }
        walNum = db.currentVersion().logNumber();
        db.closeWithoutFlush();

        // Simulate a torn write at the tail of the WAL.
        Path walPath = dir.resolve(String.format("%06d.log", walNum));
        long size = Files.size(walPath);
        try (RandomAccessFile raf = new RandomAccessFile(walPath.toFile(), "rw")) {
            raf.setLength(Math.max(0L, size - 50L));
        }

        try (LevelDB recovered = LevelDB.open(dir)) {
            int recoveredCount = 0;
            for (int i = 0; i < 50; i++) {
                if (recovered.get(Key.of(String.format("k%04d", i))).isPresent()) {
                    recoveredCount++;
                }
            }
            // The torn-write tolerance in LogReader should mean we recover MOST records but
            // drop the trailing ones cleanly — no exception, no false data.
            assertThat(recoveredCount).isGreaterThan(20);
            assertThat(recoveredCount).isLessThanOrEqualTo(50);
        }
    }

    @Test
    void recoveryAcrossL0FilesAndMemTable(@TempDir Path dir) throws IOException {
        // First batch: write + clean flush => L0 SSTable.
        LevelDB db = LevelDB.open(dir);
        for (int i = 0; i < 30; i++) {
            db.put(Key.of(String.format("flushed-%03d", i)), Slice.of("f" + i));
        }
        db.flush();
        // Second batch: write + crash before flush => only in WAL.
        for (int i = 0; i < 30; i++) {
            db.put(Key.of(String.format("memtable-%03d", i)), Slice.of("m" + i));
        }
        db.closeWithoutFlush();

        try (LevelDB recovered = LevelDB.open(dir)) {
            // Flushed batch readable from the L0 SSTable.
            for (int i = 0; i < 30; i++) {
                assertThat(new String(
                    recovered.get(Key.of(String.format("flushed-%03d", i))).get().toBytes()))
                    .isEqualTo("f" + i);
            }
            // MemTable batch readable via WAL replay.
            for (int i = 0; i < 30; i++) {
                assertThat(new String(
                    recovered.get(Key.of(String.format("memtable-%03d", i))).get().toBytes()))
                    .isEqualTo("m" + i);
            }
        }
    }

    @Test
    void recoveryHonorsTombstone(@TempDir Path dir) throws IOException {
        LevelDB db = LevelDB.open(dir);
        db.put(Key.of("k"), Slice.of("alive"));
        db.delete(Key.of("k"));
        db.closeWithoutFlush();

        try (LevelDB recovered = LevelDB.open(dir)) {
            // The newer tombstone (replayed from WAL) must shadow the prior Put.
            assertThat(recovered.get(Key.of("k"))).isEmpty();
        }
    }

    @Test
    void orphanLdbTmpFilesAreSweptOnOpen(@TempDir Path dir) throws IOException {
        try (LevelDB db = LevelDB.open(dir)) {
            db.put(Key.of("k"), Slice.of("v"));
            db.flush();
        }
        // Drop a junk .ldb.tmp into the directory.
        Path tmpFile = dir.resolve("999999.ldb.tmp");
        Files.write(tmpFile, new byte[] {1, 2, 3, 4});
        assertThat(Files.exists(tmpFile)).isTrue();

        try (LevelDB db = LevelDB.open(dir)) {
            // Orphan tmp file should be gone.
            assertThat(Files.exists(tmpFile)).isFalse();
            // Real data still there.
            assertThat(new String(db.get(Key.of("k")).get().toBytes())).isEqualTo("v");
        }
    }
}
