package com.ljarocki.shadedjar;

/**
 * On-disk format shared between {@link PackAction} (writer) and {@link FatJarTask}
 * (reader/assembler).
 *
 * <p>Each source of the fat JAR — one dependency jar, or one classes/resource
 * directory — is packed independently and in parallel into a single "part" file.
 * A part file is a flat sequence of entry records:
 *
 * <pre>
 *   int    nameLen        (UTF-8 byte length of the entry name)
 *   byte[] name           (entry name, '/'-separated, no leading '/')
 *   int    method         (0 = STORE, 8 = DEFLATE)
 *   long   crc            (CRC-32 of the *uncompressed* bytes; low 32 bits used)
 *   long   compSize       (payload length that follows)
 *   long   rawSize        (uncompressed length)
 *   byte[] payload        (compSize bytes: raw DEFLATE stream, or stored bytes)
 * </pre>
 *
 * Records are written with {@link java.io.DataOutputStream} (big-endian); the
 * assembler re-frames them into little-endian ZIP local headers. Entry
 * timestamps are normalized to a constant (see {@link #DOS_TIME}/{@link #DOS_DATE})
 * so the archive is byte-reproducible and does not depend on file mtimes.
 * Directory entries are never emitted — a fat JAR does not need them and dropping
 * them avoids a large class of cross-jar duplicate noise.
 */
final class PartFormat {
    private PartFormat() {}

    // ZIP wire constants (little-endian on disk).
    static final int LFH_SIG = 0x04034b50;
    static final int CDH_SIG = 0x02014b50;
    static final int EOCD_SIG = 0x06054b50;
    static final int FLAG_UTF8 = 0x0800;

    static final int METHOD_STORE = 0;
    static final int METHOD_DEFLATE = 8;

    // Normalized MS-DOS time/date: 1980-01-01 00:00:00, matching Gradle's
    // preserveFileTimestamps=false. Keeps output reproducible.
    static final int DOS_TIME = 0;
    static final int DOS_DATE = 0x21;
}
