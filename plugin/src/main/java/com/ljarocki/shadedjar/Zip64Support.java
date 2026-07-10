package com.ljarocki.shadedjar;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Pure, no-I/O helpers for the Zip64 extensions {@link FatJarTask} needs once an
 * archive exceeds the plain ZIP format's 32-bit/16-bit limits: more than 65,534
 * entries, a single entry's compressed/uncompressed size or offset past ~4 GiB,
 * or a central directory that starts or ends past the 4 GiB mark.
 *
 * <p>Kept separate from {@link FatJarTask} and free of any I/O so the exact byte
 * layout can be unit tested directly against synthetic huge sizes, without
 * needing multi-gigabyte fixtures.
 *
 * <p>Every extra field here follows the "report every relevant field, even ones
 * that would still technically fit" convention: whenever any one of an entry's
 * size/offset fields needs Zip64, all of its Zip64-eligible fields are written
 * into the extra field and all of the corresponding fixed-width slots are set
 * to the {@code 0xFFFFFFFF} sentinel. That keeps the "which fixed fields are
 * sentineled vs. which extra-field values are present" pairing unambiguous, at
 * the cost of a few extra bytes on entries that only need Zip64 because a
 * sibling field overflowed.
 */
final class Zip64Support {
    private Zip64Support() {}

    static final int EOCD_SIG = 0x06064b50;
    static final int LOCATOR_SIG = 0x07064b50;
    static final int EXTRA_ID = 0x0001;

    /** Largest value a standard 32-bit ZIP size/offset field can hold; 0xFFFFFFFF is reserved. */
    static final long MAX_STANDARD_UINT32 = 0xFFFFFFFEL;
    /** Largest entry count the classic EOCD can hold; 0xFFFF is reserved. */
    static final int MAX_STANDARD_ENTRIES = 0xFFFE;

    static final byte[] NO_EXTRA = new byte[0];

    static boolean needsZip64(long... values) {
        for (long v : values) {
            if (v > MAX_STANDARD_UINT32) return true;
        }
        return false;
    }

    /** Local file header Zip64 extra field: original size, then compressed size. */
    static byte[] localExtra(long rawSize, long compSize) {
        ByteBuffer b = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) EXTRA_ID);
        b.putShort((short) 16);
        b.putLong(rawSize);
        b.putLong(compSize);
        return b.array();
    }

    /** Central directory Zip64 extra field: original size, compressed size, then local header offset. */
    static byte[] centralExtra(long rawSize, long compSize, long localHeaderOffset) {
        ByteBuffer b = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) EXTRA_ID);
        b.putShort((short) 24);
        b.putLong(rawSize);
        b.putLong(compSize);
        b.putLong(localHeaderOffset);
        return b.array();
    }

    /** The Zip64 End Of Central Directory record (APPNOTE 4.3.14), no extensible data sector. */
    static byte[] eocdRecord(long totalEntries, long cdSize, long cdOffset) {
        ByteBuffer b = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(EOCD_SIG);
        b.putLong(44); // size of the remaining record: 56 bytes total - 4 (sig) - 8 (this field)
        b.putShort((short) 45); // version made by
        b.putShort((short) 45); // version needed to extract
        b.putInt(0);  // number of this disk
        b.putInt(0);  // disk with the start of the central directory
        b.putLong(totalEntries); // entries on this disk
        b.putLong(totalEntries); // entries total
        b.putLong(cdSize);
        b.putLong(cdOffset);
        return b.array();
    }

    /** The Zip64 End Of Central Directory Locator (APPNOTE 4.3.15): points at the record above. */
    static byte[] locatorRecord(long zip64EocdOffset) {
        ByteBuffer b = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(LOCATOR_SIG);
        b.putInt(0); // disk with the start of the zip64 EOCD record
        b.putLong(zip64EocdOffset);
        b.putInt(1); // total number of disks
        return b.array();
    }
}
