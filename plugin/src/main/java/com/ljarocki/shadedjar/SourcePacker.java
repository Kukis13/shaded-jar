package com.ljarocki.shadedjar;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
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
        try (ZipFile zf = new ZipFile(jar)) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            byte[] buf = new byte[BUF];
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.isDirectory()) continue;
                byte[] raw = readFully(zf.getInputStream(e), buf, (int) Math.max(0, e.getSize()));
                processEntry(out, e.getName(), raw, deflater, relocator);
            }
        }
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

        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        out.writeInt(nameBytes.length);
        out.write(nameBytes);
        out.writeInt(method);
        out.writeLong(crc.getValue());
        out.writeLong(payload.length);
        out.writeLong(raw.length);
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
}
