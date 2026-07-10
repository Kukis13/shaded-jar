package com.ljarocki.shadedjar;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Builds a fat (uber) JAR by merging the project's own output and every runtime
 * dependency into one archive. Each source is (re)compressed in parallel on an
 * internal thread pool sized by {@link #getThreads()} (default = CPU count;
 * {@code 1} = fully sequential); a single assembler thread then streams the parts
 * into a valid, reproducible JAR with first-wins duplicate handling.
 *
 * <p>With no {@link #getRelocations() relocations} this is a plain fat JAR; with
 * relocations it is a shaded JAR (packages rewritten by ASM in the workers).
 * Duplicate strategy is first-wins in classpath order (so project classes beat
 * deps), except {@code META-INF/services/*} files, which are merged across all
 * sources. Signature files and dependency manifests are stripped; a fresh
 * manifest with an optional {@code Main-Class} is generated.
 */
@CacheableTask
public abstract class FatJarTask extends DefaultTask {

    /**
     * Everything to bundle: the project's classes/resource directories followed
     * by the runtime-dependency jars. Order matters — earlier entries win
     * duplicates. {@code @Classpath} makes up-to-date checks ignore jar
     * timestamps/order-insensitive metadata, giving proper incrementality.
     */
    @Classpath
    public abstract ConfigurableFileCollection getClasspath();

    @OutputFile
    public abstract RegularFileProperty getArchiveFile();

    /** {@code Main-Class} manifest attribute; if unset, the jar is not executable. */
    @Input
    @Optional
    public abstract Property<String> getMainClass();

    /** Extra manifest main-attributes to add. */
    @Input
    @Optional
    public abstract MapProperty<String, String> getManifestAttributes();

    /** DEFLATE level, -1 = zlib default (6). */
    @Input
    @Optional
    public abstract Property<Integer> getLevel();

    /** STORE everything instead of DEFLATE (fastest, ~larger). */
    @Input
    @Optional
    public abstract Property<Boolean> getStore();

    /**
     * Package relocations: source dotted prefix -> shaded dotted prefix. Empty
     * (the default) means a plain fat JAR; any entry turns on shading.
     */
    @Input
    @Optional
    public abstract MapProperty<String, String> getRelocations();

    /** Max worker threads for the parallel pack stage; default = CPU count, {@code 1} = sequential. */
    @Input
    @Optional
    public abstract Property<Integer> getThreads();

    private static final int BUF = 1 << 16;
    private static final String SERVICES = "META-INF/services/";

    /** META-INF/*.SF|DSA|RSA|EC and SIG-* — invalid once contents are repackaged. */
    private static final Pattern SIGNATURE =
            Pattern.compile("META-INF/([^/]*\\.(SF|DSA|RSA|EC)|SIG-[^/]*)", Pattern.CASE_INSENSITIVE);

    @TaskAction
    public void run() throws Exception {
        int level = getLevel().getOrElse(-1);
        boolean store = getStore().getOrElse(false);
        Map<String, String> relocations = getRelocations().getOrElse(Collections.emptyMap());
        int threads = Math.max(1, getThreads().getOrElse(Runtime.getRuntime().availableProcessors()));

        // Resolve sources in declared order; keep only things that exist.
        List<File> sources = new ArrayList<>();
        for (File f : getClasspath().getFiles()) {
            if (f.exists()) sources.add(f);
        }

        File outFile = getArchiveFile().getAsFile().get();
        File partsDir = new File(getTemporaryDir(), "parts");
        deleteRecursively(partsDir);
        partsDir.mkdirs();

        long t0 = System.nanoTime();

        // --- Parallel phase: one task per source on a fixed pool (threads=1 -> sequential) ---
        SourcePacker packer = new SourcePacker(level, store, relocations);
        List<File> parts = new ArrayList<>(sources.size());
        for (int i = 0; i < sources.size(); i++) {
            parts.add(new File(partsDir, i + ".part"));
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>(sources.size());
            for (int i = 0; i < sources.size(); i++) {
                final File src = sources.get(i);
                final File part = parts.get(i);
                futures.add(pool.submit(() -> { packer.pack(src, part); return null; }));
            }
            for (Future<?> f : futures) f.get(); // wait + surface failures
        } catch (ExecutionException ee) {
            throw new GradleException("shaded-jar: packing failed", ee.getCause());
        } finally {
            pool.shutdown();
        }
        long tPack = System.nanoTime();

        // --- Sequential phase: assemble parts into one jar ---
        Assembly a = assemble(parts, outFile);
        long tEnd = System.nanoTime();

        deleteRecursively(partsDir);
        getLogger().lifecycle(String.format(
                "shaded-jar: %d sources -> %d entries, %.1f MiB  (dropped %d dup/filtered)  "
                        + "threads=%d pack=%.0fms assemble=%.0fms TOTAL=%.0fms",
                sources.size(), a.entryCount, a.archiveSize / 1048576.0, a.dropped,
                threads, (tPack - t0) / 1e6, (tEnd - tPack) / 1e6, (tEnd - t0) / 1e6));
    }

    private static final class Assembly {
        int entryCount;
        int dropped;
        long archiveSize;
    }

    /** Metadata retained per written entry for the central directory. */
    private static final class CdEntry {
        final byte[] name;
        final int method;
        final long crc, compSize, rawSize, offset;
        CdEntry(byte[] name, int method, long crc, long compSize, long rawSize, long offset) {
            this.name = name; this.method = method; this.crc = crc;
            this.compSize = compSize; this.rawSize = rawSize; this.offset = offset;
        }
    }

    private Assembly assemble(List<File> parts, File outFile) throws IOException {
        Assembly a = new Assembly();
        outFile.getParentFile().mkdirs();
        List<CdEntry> central = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        // SPI files are merged, not first-wins: name -> ordered unique providers.
        Map<String, LinkedHashSet<String>> services = new LinkedHashMap<>();
        long[] offset = {0};

        try (OutputStream raw = Files.newOutputStream(outFile.toPath());
             OutputStream os = new BufferedOutputStream(raw, 1 << 20)) {

            // Our generated manifest always goes first and wins.
            byte[] manifestBytes = buildManifest();
            seen.add("META-INF/MANIFEST.MF");
            writeStored(os, "META-INF/MANIFEST.MF", manifestBytes, central, offset);

            byte[] buf = new byte[BUF];
            for (File part : parts) {
                try (InputStream pin = Files.newInputStream(part.toPath());
                     DataInputStream din = new DataInputStream(new java.io.BufferedInputStream(pin, 1 << 20))) {
                    while (true) {
                        String name;
                        try {
                            int nameLen = din.readInt();
                            byte[] nb = new byte[nameLen];
                            din.readFully(nb);
                            name = new String(nb, StandardCharsets.UTF_8);
                        } catch (EOFException eof) {
                            break; // end of this part
                        }
                        int method = din.readInt();
                        long crc = din.readLong();
                        long compSize = din.readLong();
                        long rawSize = din.readLong();

                        // Service files are accumulated and merged, not written now.
                        if (isServiceFile(name)) {
                            byte[] body = readEntryBytes(din, method, compSize, rawSize);
                            accumulateService(services, name, body);
                            continue;
                        }
                        if (isFiltered(name) || !seen.add(name)) {
                            skipFully(din, compSize);
                            a.dropped++;
                            continue;
                        }
                        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
                        long headerLen = writeLocalHeader(os, nameBytes, method, crc, compSize, rawSize);
                        long entryOffset = offset[0];
                        offset[0] += headerLen;
                        copy(din, os, compSize, buf);
                        offset[0] += compSize;
                        central.add(new CdEntry(nameBytes, method, crc, compSize, rawSize, entryOffset));
                    }
                }
            }

            // Emit the merged service files (deterministic: sorted by name).
            List<String> svcNames = new ArrayList<>(services.keySet());
            Collections.sort(svcNames);
            for (String svc : svcNames) {
                if (!seen.add(svc)) continue;
                StringBuilder sb = new StringBuilder();
                for (String provider : services.get(svc)) sb.append(provider).append('\n');
                writeStored(os, svc, sb.toString().getBytes(StandardCharsets.UTF_8), central, offset);
            }

            long cdOffset = offset[0];
            byte[] cd = buildCentralDirectory(central, cdOffset);
            os.write(cd);
            a.archiveSize = cdOffset + cd.length;
        }

        a.entryCount = central.size();
        return a;
    }

    private static boolean isServiceFile(String name) {
        return name.startsWith(SERVICES) && name.length() > SERVICES.length();
    }

    /** Add a service file's providers (one per line, {@code #} comments ignored). */
    private static void accumulateService(Map<String, LinkedHashSet<String>> services,
                                          String name, byte[] body) {
        LinkedHashSet<String> providers = services.computeIfAbsent(name, k -> new LinkedHashSet<>());
        for (String line : new String(body, StandardCharsets.UTF_8).split("\n", -1)) {
            int hash = line.indexOf('#');
            String p = (hash >= 0 ? line.substring(0, hash) : line).trim();
            if (!p.isEmpty()) providers.add(p);
        }
    }

    /** Read one entry's payload, inflating if it was DEFLATE'd. */
    private static byte[] readEntryBytes(DataInputStream din, int method, long compSize, long rawSize)
            throws IOException {
        byte[] comp = new byte[(int) compSize];
        din.readFully(comp);
        if (method == PartFormat.METHOD_STORE) return comp;
        Inflater inflater = new Inflater(true);
        inflater.setInput(comp);
        byte[] out = new byte[(int) rawSize];
        try {
            int off = 0;
            while (off < out.length && !inflater.finished()) {
                off += inflater.inflate(out, off, out.length - off);
            }
            return out;
        } catch (DataFormatException ex) {
            throw new IOException("corrupt DEFLATE stream in part", ex);
        } finally {
            inflater.end();
        }
    }

    private byte[] buildManifest() throws IOException {
        Manifest manifest = new Manifest();
        Attributes attrs = manifest.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (getMainClass().isPresent() && !getMainClass().get().isEmpty()) {
            attrs.put(Attributes.Name.MAIN_CLASS, getMainClass().get());
        }
        if (getManifestAttributes().isPresent()) {
            for (Map.Entry<String, String> e : getManifestAttributes().get().entrySet()) {
                attrs.put(new Attributes.Name(e.getKey()), e.getValue());
            }
        }
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        manifest.write(bos);
        return bos.toByteArray();
    }

    private boolean isFiltered(String name) {
        if (name.equalsIgnoreCase("META-INF/MANIFEST.MF")) return true;
        if (name.equalsIgnoreCase("META-INF/INDEX.LIST")) return true;
        return SIGNATURE.matcher(name).matches();
    }

    // --- ZIP writing (little-endian), adapted from the ELEGANT_ZIP engine ---

    private void writeStored(OutputStream os, String name, byte[] data, List<CdEntry> central,
                             long[] offset) throws IOException {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(data);
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        long headerLen = writeLocalHeader(os, nameBytes, PartFormat.METHOD_STORE,
                crc.getValue(), data.length, data.length);
        long entryOffset = offset[0];
        os.write(data);
        offset[0] += headerLen + data.length;
        central.add(new CdEntry(nameBytes, PartFormat.METHOD_STORE,
                crc.getValue(), data.length, data.length, entryOffset));
    }

    private long writeLocalHeader(OutputStream os, byte[] nameBytes, int method,
                                  long crc, long compSize, long rawSize) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(30 + nameBytes.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(PartFormat.LFH_SIG);
        b.putShort((short) 20);
        b.putShort((short) PartFormat.FLAG_UTF8);
        b.putShort((short) method);
        b.putShort((short) PartFormat.DOS_TIME);
        b.putShort((short) PartFormat.DOS_DATE);
        b.putInt((int) crc);
        b.putInt((int) compSize);
        b.putInt((int) rawSize);
        b.putShort((short) nameBytes.length);
        b.putShort((short) 0);
        b.put(nameBytes);
        os.write(b.array());
        return b.array().length;
    }

    private byte[] buildCentralDirectory(List<CdEntry> central, long cdOffset) {
        if (central.size() > 0xFFFF) {
            throw new GradleException("shaded-jar: " + central.size() + " entries exceeds the "
                    + "65535-entry ZIP limit; Zip64 is not supported yet (planned for a later phase).");
        }
        int size = 0;
        for (CdEntry e : central) size += 46 + e.name.length;
        if (cdOffset > 0xFFFFFFFFL || (long) size + cdOffset > 0xFFFFFFFFL) {
            throw new GradleException("shaded-jar: archive exceeds 4 GiB; Zip64 is not supported "
                    + "yet (planned for a later phase).");
        }
        ByteBuffer b = ByteBuffer.allocate(size + 22).order(ByteOrder.LITTLE_ENDIAN);
        for (CdEntry e : central) {
            b.putInt(PartFormat.CDH_SIG);
            b.putShort((short) 20);
            b.putShort((short) 20);
            b.putShort((short) PartFormat.FLAG_UTF8);
            b.putShort((short) e.method);
            b.putShort((short) PartFormat.DOS_TIME);
            b.putShort((short) PartFormat.DOS_DATE);
            b.putInt((int) e.crc);
            b.putInt((int) e.compSize);
            b.putInt((int) e.rawSize);
            b.putShort((short) e.name.length);
            b.putShort((short) 0);
            b.putShort((short) 0);
            b.putShort((short) 0);
            b.putShort((short) 0);
            b.putInt(0);
            b.putInt((int) e.offset);
            b.put(e.name);
        }
        int count = central.size();
        b.putInt(PartFormat.EOCD_SIG);
        b.putShort((short) 0);
        b.putShort((short) 0);
        b.putShort((short) count);
        b.putShort((short) count);
        b.putInt(size);
        b.putInt((int) cdOffset);
        b.putShort((short) 0);
        byte[] out = new byte[b.position()];
        b.flip();
        b.get(out);
        return out;
    }

    private static void copy(InputStream in, OutputStream out, long n, byte[] buf) throws IOException {
        long remaining = n;
        while (remaining > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, remaining));
            if (r < 0) throw new EOFException("truncated part stream");
            out.write(buf, 0, r);
            remaining -= r;
        }
    }

    private static void skipFully(DataInputStream in, long n) throws IOException {
        long remaining = n;
        while (remaining > 0) {
            long s = in.skip(remaining);
            if (s <= 0) {
                if (in.read() < 0) throw new EOFException("truncated part stream");
                remaining--;
            } else {
                remaining -= s;
            }
        }
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursively(k);
        f.delete();
    }
}
