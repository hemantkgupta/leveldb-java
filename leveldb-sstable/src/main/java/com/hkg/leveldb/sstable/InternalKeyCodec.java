package com.hkg.leveldb.sstable;

import com.hkg.leveldb.common.InternalKey;
import com.hkg.leveldb.common.Key;
import com.hkg.leveldb.common.SequenceNumber;
import com.hkg.leveldb.common.ValueType;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Serialise an {@link InternalKey} as the LevelDB on-disk form:
 *   {@code userKeyBytes || ((seq << 8) | type) as 8 B LE}.
 *
 * <p>The byte form is what gets stored in SSTable blocks. Comparison over
 * the byte form must match {@link InternalKey#compareTo(InternalKey)} —
 * userKey ASC, sequence DESC, type ASC. See {@link #compareInternalBytes}.
 */
public final class InternalKeyCodec {

    private InternalKeyCodec() {}

    public static final int TRAILER_LEN = 8;

    public static byte[] encode(InternalKey ik) {
        byte[] userKey = ik.userKey().bytes();
        ByteBuffer buf = ByteBuffer.allocate(userKey.length + TRAILER_LEN).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(userKey);
        long packed = (ik.sequence().value() << 8) | (ik.type().tag() & 0xFFL);
        buf.putLong(packed);
        return buf.array();
    }

    public static InternalKey decode(byte[] data) {
        if (data.length < TRAILER_LEN) {
            throw new SsTableFormatException("internal key bytes too short: " + data.length);
        }
        int userKeyLen = data.length - TRAILER_LEN;
        byte[] userKey = new byte[userKeyLen];
        System.arraycopy(data, 0, userKey, 0, userKeyLen);
        long packed = ByteBuffer.wrap(data, userKeyLen, TRAILER_LEN)
            .order(ByteOrder.LITTLE_ENDIAN).getLong();
        long seq = packed >>> 8;
        byte tag = (byte) (packed & 0xFFL);
        return new InternalKey(Key.of(userKey), new SequenceNumber(seq), ValueType.fromTag(tag));
    }

    /**
     * Compare two raw internal-key byte arrays. Mirrors {@link InternalKey#compareTo}:
     * userKey ASC, sequence DESC, type ASC.
     */
    public static int compareInternalBytes(byte[] a, byte[] b) {
        int aUserLen = a.length - TRAILER_LEN;
        int bUserLen = b.length - TRAILER_LEN;
        if (aUserLen < 0 || bUserLen < 0) {
            throw new SsTableFormatException("internal key bytes too short for trailer");
        }
        int n = Math.min(aUserLen, bUserLen);
        for (int i = 0; i < n; i++) {
            int diff = (a[i] & 0xff) - (b[i] & 0xff);
            if (diff != 0) return diff;
        }
        if (aUserLen != bUserLen) return aUserLen - bUserLen;
        long pa = ByteBuffer.wrap(a, aUserLen, TRAILER_LEN).order(ByteOrder.LITTLE_ENDIAN).getLong();
        long pb = ByteBuffer.wrap(b, bUserLen, TRAILER_LEN).order(ByteOrder.LITTLE_ENDIAN).getLong();
        long sa = pa >>> 8;
        long sb = pb >>> 8;
        if (sa != sb) return Long.compare(sb, sa); // sequence DESC
        byte ta = (byte) (pa & 0xff);
        byte tb = (byte) (pb & 0xff);
        return Byte.compare(ta, tb);
    }

    /** Extract just the user-key bytes from a raw internal-key byte array. */
    public static byte[] userKeyOf(byte[] internalKeyBytes) {
        int userLen = internalKeyBytes.length - TRAILER_LEN;
        if (userLen < 0) {
            throw new SsTableFormatException("internal key bytes too short for trailer");
        }
        byte[] out = new byte[userLen];
        System.arraycopy(internalKeyBytes, 0, out, 0, userLen);
        return out;
    }

    /** Extract the sequence number from a raw internal-key byte array. */
    public static long sequenceOf(byte[] internalKeyBytes) {
        int userLen = internalKeyBytes.length - TRAILER_LEN;
        long packed = ByteBuffer.wrap(internalKeyBytes, userLen, TRAILER_LEN)
            .order(ByteOrder.LITTLE_ENDIAN).getLong();
        return packed >>> 8;
    }

    /** Extract the value-type tag byte from a raw internal-key byte array. */
    public static byte tagOf(byte[] internalKeyBytes) {
        int userLen = internalKeyBytes.length - TRAILER_LEN;
        long packed = ByteBuffer.wrap(internalKeyBytes, userLen, TRAILER_LEN)
            .order(ByteOrder.LITTLE_ENDIAN).getLong();
        return (byte) (packed & 0xff);
    }
}
