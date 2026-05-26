package com.hkg.leveldb.common;

import java.util.Objects;

/**
 * The LevelDB internal key: {@code (userKey, sequence, type)}. Ordering is
 * {@code (userKey ASC, sequence DESC, type)} so that the newest version of
 * a given user key sorts ahead of older versions when iterating an SSTable
 * or MemTable. This is the invariant the read path relies on.
 */
public record InternalKey(Key userKey, SequenceNumber sequence, ValueType type)
    implements Comparable<InternalKey> {

    public InternalKey {
        Objects.requireNonNull(userKey, "userKey");
        Objects.requireNonNull(sequence, "sequence");
        Objects.requireNonNull(type, "type");
    }

    @Override
    public int compareTo(InternalKey other) {
        int c = this.userKey.compareTo(other.userKey);
        if (c != 0) return c;
        // Newer sequence (larger value) sorts FIRST — descending sequence order.
        int s = Long.compare(other.sequence.value(), this.sequence.value());
        if (s != 0) return s;
        // Stable tie-break on tag (Value=1, Deletion=0). Same sequence on the same
        // user key should not normally happen, but tie-break deterministically.
        return Byte.compare(this.type.tag(), other.type.tag());
    }
}
