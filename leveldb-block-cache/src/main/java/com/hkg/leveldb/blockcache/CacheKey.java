package com.hkg.leveldb.blockcache;

import com.hkg.leveldb.common.FileNumber;

import java.util.Objects;

/**
 * Identifies a single SSTable block in the block cache. LevelDB's cache key is
 * the pair (file number, block offset) — the file number disambiguates blocks
 * across SSTables, the offset disambiguates blocks within one SSTable.
 */
public record CacheKey(FileNumber file, long blockOffset) {

    public CacheKey {
        Objects.requireNonNull(file, "file");
        if (blockOffset < 0L) {
            throw new IllegalArgumentException("blockOffset must be non-negative, got " + blockOffset);
        }
    }
}
