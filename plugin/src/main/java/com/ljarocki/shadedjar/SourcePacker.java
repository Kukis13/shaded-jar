package com.ljarocki.shadedjar;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Packs one source (a dependency JAR or a classes/resource directory) into a
 * "part" file holding one record per file entry (see {@link PartFormat}). This is
 * where the CPU-heavy work lives — DEFLATE and, when relocating, ASM bytecode
 * rewriting.
 *
 * <p>Dependency jar entries are already DEFLATE-compressed. When an entry's
 * content won't be touched (no relocation running at all, or — for non-class,
 * non-service entries — relocation that only ever changes paths, never bytes),
 * {@link #packJar} copies the compressed stream straight out of the source
 * jar's local file header instead of inflating and re-deflating it (see
 * {@link #readCentralDirectory} / {@link #readVerbatimPayload}). Class files
 * are only eligible when there is no relocator at all: even a class whose own
 * path doesn't match a rule may reference a class that does, so its bytecode
 * must go through ASM whenever any relocation is configured. Falls back to the
 * normal decode/recompress path per-entry, or for the whole jar, on anything
 * that doesn't fit the plain (non-Zip64) central directory this reads.
 *
 * <p>Instances are stateless beyond their config, so {@link #pack} may be called
 * concurrently for different sources: each call uses its own {@link Deflater} and
 * {@link Relocator}. {@link PackAction} runs one {@code pack} per source on
 * Gradle's worker pool (bounded by {@code --max-workers}).
 */
final class SourcePacker {

    private static final int BUF = 1 << 16;
    private static final String SERVICES = "META-INF/services/";

    private final int level;
    private final boolean store;
    private final Map<String, String> relocations;

    SourcePacker(int level, boolean store, Map<String, String> relocations) {
        this.level = level;
        this.store = store;
        this.relocations = relocations;
    }

    void pack(File source, File part) throws IOException {
        Relocator relocator = new Relocator(relocations);
        try (OutputStream fos = Files.newOutputStream(part.toPath());
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(fos, 1 << 20))) {
            Deflater deflater = new Deflater(level, true);
            try {
                if (source.isDirectory()) {
                    packDirectory(source.toPath(), out, deflater, relocator);
                } else {
                    packJar(source, out, deflater, relocator);
                }
            } finally {
                deflater.end();
            }
        } catch (IOException ex) {
            throw new IOException("shaded-jar: failed packing " + source, ex);
        }
    }

    private void packDirectory(Path root, DataOutputStream out, Deflater deflater, Relocator relocator)
            throws IOException {
        List<Path> files = new ArrayList<>();
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(files::add);
        }
        // Stable order regardless of filesystem enumeration order.
        files.sort(Comparator.naturalOrder());
        for (Path p : files) {
            String name = root.relativize(p).toString().replace('\\', '/');
            processEntry(out, name, Files.readAllBytes(p), deflater, relocator);
        }
    }

    private void packJar(File jar, DataOutputStream out, Deflater deflater, Relocator relocator)
            throws IOException {
        // Best-effort index of the source jar's central directory, used to copy
        // already-compressed entries verbatim. Null means "couldn't parse it
        // cheaply/safely" (e.g. Zip64) — fall back to the normal path entirely.
        Map<String, RawEntry> rawIndex = readCentralDirectory(jar);
        try (ZipFile zf = new ZipFile(jar);
             RandomAccessFile raf = rawIndex != null ? new RandomAccessFile(jar, "r") : null) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            byte[] buf = new byte[BUF];
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.isDirectory()) continue;
                String name = e.getName();

                RawEntry raw = rawIndex != null ? rawIndex.get(name) : null;
                if (raw != null && !store && raw.method == PartFormat.METHOD_DEFLATE
                        && isVerbatimEligible(name, relocator)) {
                    try {
                        String outName = name.endsWith(".class") ? name
                                : (relocator.isEmpty() ? name : relocator.relocateEntryName(name));
                        byte[] payload = readVerbatimPayload(raf, raw);
                        writeRawRecord(out, outName, PartFormat.METHOD_DEFLATE, raw.crc, payload, raw.rawSize);
                        continue;
                    } catch (IOException ex) {
                        // Malformed local header for this one entry: fall through to the
                        // safe decode/recompress path below instead of failing the build.
                    }
                }

                byte[] data = readFully(zf.getInputStream(e), buf, (int) Math.max(0, e.getSize()));
                processEntry(out, name, data, deflater, relocator);
            }
        }
    }

    /**
     * Whether {@code name} can be copied byte-for-byte from the source jar
     * without decoding: service files always need decoding (to merge/relocate
     * their content); class files need it only when relocation is active at
     * all (their bytecode may reference a relocated type even if their own
     * path doesn't match a rule); everything else is content-unchanged by
     * relocation (only its path may be renamed), so it's always eligible.
     */
    private static boolean isVerbatimEligible(String name, Relocator relocator) {
        if (name.startsWith(SERVICES) && name.length() > SERVICES.length()) return false;
        if (name.endsWith(".class")) return relocator.isEmpty();
        return true;
    }

    /**
     * Apply relocation (if any) to one entry, then emit its record. Service files
     * are always STORE'd so the assembler can read and merge them cheaply.
     */
    private void processEntry(DataOutputStream out, String name, byte[] raw, Deflater deflater,
                              Relocator relocator) throws IOException {
        boolean isService = name.startsWith(SERVICES) && name.length() > SERVICES.length();
        String outName = name;
        byte[] data = raw;

        if (!relocator.isEmpty()) {
            if (name.endsWith(".class")) {
                data = relocator.relocateClass(raw);
                outName = relocator.relocateEntryName(name);
            } else if (isService) {
                outName = relocator.relocateServiceFileName(name);
                data = Relocator.utf8(relocator.relocateServiceContent(
                        new String(raw, StandardCharsets.UTF_8)));
            } else {
                outName = relocator.relocateEntryName(name);
            }
        }
        writeRecord(out, outName, data, deflater, store || isService);
    }

    /** Compress {@code raw} and append one record to the part stream. */
    private void writeRecord(DataOutputStream out, String name, byte[] raw, Deflater deflater,
                             boolean forceStore) throws IOException {
        CRC32 crc = new CRC32();
        crc.update(raw);

        int method;
        byte[] payload;
        if (forceStore || raw.length == 0) {
            method = PartFormat.METHOD_STORE;
            payload = raw;
        } else {
            deflater.reset();
            deflater.setInput(raw);
            deflater.finish();
            ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(64, raw.length / 2));
            byte[] tmp = new byte[BUF];
            while (!deflater.finished()) {
                int n = deflater.deflate(tmp);
                bos.write(tmp, 0, n);
            }
            byte[] comp = bos.toByteArray();
            if (comp.length >= raw.length) { // deflate didn't help (already-compressed data)
                method = PartFormat.METHOD_STORE;
                payload = raw;
            } else {
                method = PartFormat.METHOD_DEFLATE;
                payload = comp;
            }
        }

        writeRawRecord(out, name, method, crc.getValue(), payload, raw.length);
    }

    /** Append one already-encoded record (payload as-is, no compression here). */
    private static void writeRawRecord(DataOutputStream out, String name, int method, long crc,
                                       byte[] payload, long rawSize) throws IOException {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        out.writeInt(nameBytes.length);
        out.write(nameBytes);
        out.writeInt(method);
        out.writeLong(crc);
        out.writeLong(payload.length);
        out.writeLong(rawSize);
        out.write(payload);
    }

    private static byte[] readFully(InputStream rawIn, byte[] buf, int sizeHint) throws IOException {
        try (InputStream in = new BufferedInputStream(rawIn, BUF)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(64, sizeHint));
            int n;
            while ((n = in.read(buf)) >= 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    // --- verbatim compressed-stream copy: minimal, read-only ZIP parsing -----

    /** One source jar's central-directory record, enough to locate its raw bytes. */
    private static final class RawEntry {
        final int method;
        final long crc, compSize, rawSize, localHeaderOffset;
        RawEntry(int method, long crc, long compSize, long rawSize, long localHeaderOffset) {
            this.method = method;
            this.crc = crc;
            this.compSize = compSize;
            this.rawSize = rawSize;
            this.localHeaderOffset = localHeaderOffset;
        }
    }

    /**
     * Parses {@code jar}'s End-Of-Central-Directory + central directory to index
     * every entry's method/crc/sizes/local-header-offset, without touching any
     * entry's actual (compressed) bytes. Returns {@code null} — meaning "fall
     * back to the normal path for this whole jar" — for anything this doesn't
     * handle: a missing/non-standard EOCD, or any Zip64 marker (entry count
     * 0xFFFF, or a size/offset field pinned at 0xFFFFFFFF). Zip64 support is
     * tracked separately (see the project roadmap); it's simply out of scope
     * for the fast path here.
     */
    private static Map<String, RawEntry> readCentralDirectory(File jar) {
        try (RandomAccessFile raf = new RandomAccessFile(jar, "r")) {
            long len = raf.length();
            if (len < 22) return null;
            int window = (int) Math.min(len, 22L + 65535L);
            byte[] tail = new byte[window];
            raf.seek(len - window);
            raf.readFully(tail);

            int eocd = -1;
            for (int i = tail.length - 22; i >= 0; i--) {
                if ((tail[i] & 0xFF) == 0x50 && (tail[i + 1] & 0xFF) == 0x4B
                        && (tail[i + 2] & 0xFF) == 0x05 && (tail[i + 3] & 0xFF) == 0x06) {
                    eocd = i;
                    break;
                }
            }
            if (eocd < 0) return null;

            ByteBuffer e = ByteBuffer.wrap(tail, eocd, 22).order(ByteOrder.LITTLE_ENDIAN);
            e.getInt();     // EOCD signature
            e.getShort();   // this disk number
            e.getShort();   // disk with central directory start
            e.getShort();   // entries on this disk
            int totalEntries = e.getShort() & 0xFFFF;
            long cdSize = e.getInt() & 0xFFFFFFFFL;
            long cdOffset = e.getInt() & 0xFFFFFFFFL;
            if (totalEntries == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
                return null; // Zip64 EOCD locator required
            }

            Map<String, RawEntry> index = new HashMap<>(totalEntries * 2);
            raf.seek(cdOffset);
            byte[] header = new byte[46];
            for (int i = 0; i < totalEntries; i++) {
                raf.readFully(header);
                ByteBuffer b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
                if (b.getInt() != PartFormat.CDH_SIG) return null;
                b.getShort();               // version made by
                b.getShort();               // version needed
                b.getShort();               // general purpose bit flag
                int method = b.getShort() & 0xFFFF;
                b.getShort();               // last mod time
                b.getShort();               // last mod date
                long crc = b.getInt() & 0xFFFFFFFFL;
                long compSize = b.getInt() & 0xFFFFFFFFL;
                long rawSize = b.getInt() & 0xFFFFFFFFL;
                int nameLen = b.getShort() & 0xFFFF;
                int extraLen = b.getShort() & 0xFFFF;
                int commentLen = b.getShort() & 0xFFFF;
                b.getShort();               // disk number start
                b.getShort();               // internal file attributes
                b.getInt();                 // external file attributes
                long localHeaderOffset = b.getInt() & 0xFFFFFFFFL;

                byte[] nameBytes = new byte[nameLen];
                raf.readFully(nameBytes);
                if (extraLen + commentLen > 0) {
                    raf.seek(raf.getFilePointer() + extraLen + commentLen);
                }

                if (compSize == 0xFFFFFFFFL || rawSize == 0xFFFFFFFFL
                        || localHeaderOffset == 0xFFFFFFFFL) {
                    return null; // Zip64 extra field required to resolve the real values
                }
                String name = new String(nameBytes, StandardCharsets.UTF_8);
                index.put(name, new RawEntry(method, crc, compSize, rawSize, localHeaderOffset));
            }
            return index;
        } catch (IOException ex) {
            return null; // any parse hiccup: fall back to the always-correct slow path
        }
    }

    /** Read one entry's raw (still-compressed) payload straight from its local file header. */
    private static byte[] readVerbatimPayload(RandomAccessFile raf, RawEntry entry) throws IOException {
        raf.seek(entry.localHeaderOffset);
        byte[] lfh = new byte[30];
        raf.readFully(lfh);
        ByteBuffer b = ByteBuffer.wrap(lfh).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getInt() != PartFormat.LFH_SIG) {
            throw new IOException("unexpected local file header signature");
        }
        b.position(26); // file name length field
        int nameLen = b.getShort() & 0xFFFF;
        int extraLen = b.getShort() & 0xFFFF;

        raf.seek(entry.localHeaderOffset + 30L + nameLen + extraLen);
        byte[] payload = new byte[(int) entry.compSize];
        raf.readFully(payload);
        return payload;
    }
}
