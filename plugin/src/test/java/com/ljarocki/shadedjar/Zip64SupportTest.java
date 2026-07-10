package com.ljarocki.shadedjar;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hermetic tests for the Zip64 byte layout in {@link Zip64Support} — no Gradle,
 * no I/O, no multi-gigabyte fixtures. Exercises entries/records far past the
 * 4 GiB / 65,534-entry thresholds with synthetic numbers, since actually
 * writing that much data isn't practical in a unit test; the entry-count path
 * (more than 65,534 real entries) is additionally exercised end-to-end in
 * {@code PluginFunctionalTest}.
 */
class Zip64SupportTest {

    @Test
    void needsZip64_thresholdIsExactlyAtTheReservedSentinel() {
        assertFalse(Zip64Support.needsZip64(0));
        assertFalse(Zip64Support.needsZip64(Zip64Support.MAX_STANDARD_UINT32));
        assertTrue(Zip64Support.needsZip64(Zip64Support.MAX_STANDARD_UINT32 + 1));
        assertTrue(Zip64Support.needsZip64(0xFFFFFFFFL));
        assertTrue(Zip64Support.needsZip64(5_000_000_000L));
        // Any one of several values tripping it is enough.
        assertTrue(Zip64Support.needsZip64(1, 2, 0x1_0000_0000L));
        assertFalse(Zip64Support.needsZip64(1, 2, 3));
    }

    @Test
    void localExtra_encodesRawThenCompSizeAsTwoLongs() {
        long rawSize = 6_000_000_000L;
        long compSize = 5_000_000_000L;
        byte[] extra = Zip64Support.localExtra(rawSize, compSize);

        assertEquals(20, extra.length, "4-byte extra header + two 8-byte values");
        ByteBuffer b = ByteBuffer.wrap(extra).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals((short) Zip64Support.EXTRA_ID, b.getShort());
        assertEquals((short) 16, b.getShort());
        assertEquals(rawSize, b.getLong());
        assertEquals(compSize, b.getLong());
    }

    @Test
    void centralExtra_encodesRawCompSizeThenOffsetAsThreeLongs() {
        long rawSize = 1_000_000_000L;
        long compSize = 900_000_000L;
        long offset = 8_000_000_000L;
        byte[] extra = Zip64Support.centralExtra(rawSize, compSize, offset);

        assertEquals(28, extra.length, "4-byte extra header + three 8-byte values");
        ByteBuffer b = ByteBuffer.wrap(extra).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals((short) Zip64Support.EXTRA_ID, b.getShort());
        assertEquals((short) 24, b.getShort());
        assertEquals(rawSize, b.getLong());
        assertEquals(compSize, b.getLong());
        assertEquals(offset, b.getLong());
    }

    @Test
    void eocdRecord_hasCorrectSignatureSizeFieldAndCounts() {
        long totalEntries = 200_000L; // past the 65,534 classic-EOCD ceiling
        long cdSize = 9_000_000_000L;
        long cdOffset = 40_000_000_000L;
        byte[] rec = Zip64Support.eocdRecord(totalEntries, cdSize, cdOffset);

        assertEquals(56, rec.length);
        ByteBuffer b = ByteBuffer.wrap(rec).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(Zip64Support.EOCD_SIG, b.getInt());
        assertEquals(44L, b.getLong(), "size of remaining record");
        assertEquals((short) 45, b.getShort(), "version made by");
        assertEquals((short) 45, b.getShort(), "version needed");
        assertEquals(0, b.getInt(), "this disk number");
        assertEquals(0, b.getInt(), "disk with CD start");
        assertEquals(totalEntries, b.getLong(), "entries on this disk");
        assertEquals(totalEntries, b.getLong(), "entries total");
        assertEquals(cdSize, b.getLong());
        assertEquals(cdOffset, b.getLong());
    }

    @Test
    void locatorRecord_pointsAtTheGivenOffset() {
        long zip64EocdOffset = 49_000_000_000L;
        byte[] rec = Zip64Support.locatorRecord(zip64EocdOffset);

        assertEquals(20, rec.length);
        ByteBuffer b = ByteBuffer.wrap(rec).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(Zip64Support.LOCATOR_SIG, b.getInt());
        assertEquals(0, b.getInt(), "disk with the zip64 EOCD record");
        assertEquals(zip64EocdOffset, b.getLong());
        assertEquals(1, b.getInt(), "total number of disks");
    }
}
