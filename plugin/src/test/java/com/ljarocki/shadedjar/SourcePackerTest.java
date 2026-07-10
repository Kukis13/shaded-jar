package com.ljarocki.shadedjar;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the verbatim compressed-stream copy path in {@link SourcePacker#pack}
 * against a hand-built source jar, without going through Gradle at all.
 *
 * <p>To actually prove the fast path ran (as opposed to merely producing correct
 * output, which the slow path would too), the source jar is compressed at DEFLATE
 * level 1 while {@code SourcePacker} is configured with level 9. A verbatim-copied
 * entry's payload must be byte-identical to the source's level-1 stream and
 * different from a fresh level-9 compression of the same content.
 */
class SourcePackerTest {

    @TempDir
    Path dir;

    private static final String REPEATED_TEXT =
            ("the quick brown fox jumps over the lazy dog. ").repeat(200);

    @Test
    void plainFatJar_copiesDeflatedEntriesVerbatim_andStillMergesServices() throws Exception {
        File jar = dir.resolve("source.jar").toFile();
        byte[] textBytes = REPEATED_TEXT.getBytes(StandardCharsets.UTF_8);
        byte[] tinyBytes = {1, 2, 3, 4, 5};
        byte[] serviceBytes = "com.example.Impl\n".getBytes(StandardCharsets.UTF_8);

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(jar))) {
            zos.setLevel(1);
            putDeflated(zos, "a/Foo.txt", textBytes);
            putStored(zos, "a/tiny.bin", tinyBytes);
            putDeflated(zos, "META-INF/services/com.example.Spi", serviceBytes);
        }

        File part = dir.resolve("out.part").toFile();
        new SourcePacker(9, false, Collections.emptyMap()).pack(jar, part);

        Map<String, Record> records = readRecords(part);
        assertEquals(3, records.size());

        // Verbatim-eligible: payload must match the source's level-1 stream exactly,
        // and differ from a fresh level-9 recompression (proving no re-deflate ran).
        Record foo = records.get("a/Foo.txt");
        assertEquals(PartFormat.METHOD_DEFLATE, foo.method);
        assertArrayEquals(deflate(textBytes, 1), foo.payload, "copied verbatim from the level-1 source");
        assertNotEquals(bytesToList(deflate(textBytes, 9)), bytesToList(foo.payload),
                "must not have been recompressed at the configured level 9");
        assertArrayEquals(textBytes, inflate(foo.payload, (int) foo.rawSize));

        // STORE-sourced entry: never eligible for the fast path, but must still round-trip.
        Record tiny = records.get("a/tiny.bin");
        assertArrayEquals(tinyBytes, decode(tiny));

        // Service files are always decoded (for later merging), never copied verbatim.
        Record svc = records.get("META-INF/services/com.example.Spi");
        assertEquals(PartFormat.METHOD_STORE, svc.method);
        assertArrayEquals(serviceBytes, svc.payload);
    }

    @Test
    void shadedJar_verbatimCopiesUnrelocatedAndRenamedResources_butStillRunsAsmOnClasses()
            throws Exception {
        File jar = dir.resolve("source.jar").toFile();
        byte[] classBytes = generateClass("com/foo/Bar");
        byte[] relocatedResource = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] untouchedResource = REPEATED_TEXT.getBytes(StandardCharsets.UTF_8);

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(jar))) {
            zos.setLevel(1);
            putDeflated(zos, "com/foo/Bar.class", classBytes);
            putDeflated(zos, "com/foo/resource.txt", relocatedResource);
            putDeflated(zos, "other/Keep.txt", untouchedResource);
        }

        Map<String, String> relocations = new LinkedHashMap<>();
        relocations.put("com.foo", "shaded.foo");

        File part = dir.resolve("out.part").toFile();
        new SourcePacker(9, false, relocations).pack(jar, part);

        Map<String, Record> records = readRecords(part);
        assertEquals(3, records.size());
        assertFalse(records.containsKey("com/foo/Bar.class"), "class entry renamed");
        assertFalse(records.containsKey("com/foo/resource.txt"), "resource entry renamed");

        // Class files always go through ASM once any relocation is configured, even
        // though this class's own path matched a rule — prove it actually ran by
        // reading the class name back out of the rewritten bytecode.
        Record cls = records.get("shaded/foo/Bar.class");
        byte[] classOut = decode(cls);
        assertEquals("shaded/foo/Bar", new ClassReader(classOut).getClassName());
        assertNotEquals(bytesToList(deflate(classBytes, 9)), bytesToList(cls.payload),
                "class bytes must have been rewritten, not merely recompressed");

        // Renamed resource: content is untouched by relocation, so it's still
        // eligible for verbatim copy even though its path changed.
        Record resource = records.get("shaded/foo/resource.txt");
        assertArrayEquals(deflate(relocatedResource, 1), resource.payload,
                "renamed resource still copied verbatim from the level-1 source");

        // Untouched resource: also verbatim (content and path both unchanged).
        Record keep = records.get("other/Keep.txt");
        assertArrayEquals(deflate(untouchedResource, 1), keep.payload,
                "unrelated resource copied verbatim from the level-1 source");
    }

    // --- helpers ----------------------------------------------------------

    private static byte[] generateClass(String internalName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void putDeflated(ZipOutputStream zos, String name, byte[] content) throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setMethod(ZipEntry.DEFLATED);
        zos.putNextEntry(e);
        zos.write(content);
        zos.closeEntry();
    }

    private static void putStored(ZipOutputStream zos, String name, byte[] content) throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setMethod(ZipEntry.STORED);
        e.setSize(content.length);
        e.setCompressedSize(content.length);
        CRC32 crc = new CRC32();
        crc.update(content);
        e.setCrc(crc.getValue());
        zos.putNextEntry(e);
        zos.write(content);
        zos.closeEntry();
    }

    private static byte[] deflate(byte[] raw, int level) {
        Deflater deflater = new Deflater(level, true);
        try {
            deflater.setInput(raw);
            deflater.finish();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] tmp = new byte[4096];
            while (!deflater.finished()) {
                int n = deflater.deflate(tmp);
                bos.write(tmp, 0, n);
            }
            return bos.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static byte[] inflate(byte[] comp, int rawSize) throws IOException {
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(comp);
            byte[] out = new byte[rawSize];
            int off = 0;
            while (off < out.length && !inflater.finished()) {
                off += inflater.inflate(out, off, out.length - off);
            }
            return out;
        } catch (java.util.zip.DataFormatException ex) {
            throw new IOException(ex);
        } finally {
            inflater.end();
        }
    }

    private static byte[] decode(Record r) throws IOException {
        return r.method == PartFormat.METHOD_STORE ? r.payload : inflate(r.payload, (int) r.rawSize);
    }

    private static java.util.List<Byte> bytesToList(byte[] b) {
        java.util.List<Byte> list = new java.util.ArrayList<>(b.length);
        for (byte x : b) list.add(x);
        return list;
    }

    private static final class Record {
        final int method;
        final long rawSize;
        final byte[] payload;
        Record(int method, long rawSize, byte[] payload) {
            this.method = method;
            this.rawSize = rawSize;
            this.payload = payload;
        }
    }

    /** Mirrors the record-reading loop in {@code FatJarTask.assemble}. */
    private static Map<String, Record> readRecords(File part) throws IOException {
        Map<String, Record> out = new HashMap<>();
        try (InputStream in = Files.newInputStream(part.toPath());
             DataInputStream din = new DataInputStream(new java.io.BufferedInputStream(in))) {
            while (true) {
                String name;
                try {
                    int nameLen = din.readInt();
                    byte[] nb = new byte[nameLen];
                    din.readFully(nb);
                    name = new String(nb, StandardCharsets.UTF_8);
                } catch (EOFException eof) {
                    break;
                }
                int method = din.readInt();
                din.readLong(); // crc (not needed by these assertions)
                long compSize = din.readLong();
                long rawSize = din.readLong();
                byte[] payload = new byte[(int) compSize];
                din.readFully(payload);
                out.put(name, new Record(method, rawSize, payload));
            }
        }
        return out;
    }
}
