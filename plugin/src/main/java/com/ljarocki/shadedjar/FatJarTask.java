package com.ljarocki.shadedjar;

import org.gradle.api.DefaultTask;
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
import org.gradle.workers.WorkQueue;
import org.gradle.workers.WorkerExecutor;

import javax.inject.Inject;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
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
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Builds a fat (uber) JAR by merging the project's own output and every runtime
 * dependency into one archive. Each source is (re)compressed in parallel on
 * Gradle's worker pool (bounded by {@code --max-workers}); a single assembler
 * thread then streams the parts into a valid, reproducible JAR with first-wins
 * duplicate handling.
 *
 * <p>With no {@link #getRelocations() relocations} this is a plain fat JAR; with
 * relocations it is a shaded JAR (packages rewritten by ASM in the workers).
 * Duplicate strategy is first-wins in classpath order (so project classes beat
 * deps), except {@code META-INF/services/*} files and the well-known Spring
 * properties files ({@code spring.factories}/{@code .handlers}/{@code .schemas},
 * see {@link SpringProperties}), which are merged across all sources instead.
 * Signature files and dependency manifests are stripped; a fresh manifest with
 * an optional {@code Main-Class} is generated — and {@code Multi-Release: true}
 * if any source contributes a {@code META-INF/versions/N/...} entry, since the
 * JVM otherwise ignores that directory entirely (see {@link #buildManifest}).
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

    /** Include patterns per relocation, keyed by the same prefix as {@link #getRelocations()}. */
    @Input
    @Optional
    public abstract MapProperty<String, String> getRelocationIncludes();

    /** Exclude patterns per relocation, same shape as {@link #getRelocationIncludes()}. */
    @Input
    @Optional
    public abstract MapProperty<String, String> getRelocationExcludes();

    @Inject
    public abstract WorkerExecutor getWorkerExecutor();

    private static final int BUF = 1 << 16;
    private static final String SERVICES = "META-INF/services/";

    /** META-INF/*.SF|DSA|RSA|EC and SIG-* — invalid once contents are repackaged. */
    private static final Pattern SIGNATURE =
            Pattern.compile("META-INF/([^/]*\\.(SF|DSA|RSA|EC)|SIG-[^/]*)", Pattern.CASE_INSENSITIVE);

    /** Multi-release JAR (JEP 238) version override directory — see {@link #buildManifest}. */
    private static final Pattern MRJAR_VERSIONS = Pattern.compile("META-INF/versions/\\d+/.*");

    @TaskAction
    public void run() throws Exception {
        int level = getLevel().getOrElse(-1);
        boolean store = getStore().getOrElse(false);
        Map<String, String> relocations = getRelocations().getOrElse(Collections.emptyMap());
        Map<String, String> relocationIncludes = getRelocationIncludes().getOrElse(Collections.emptyMap());
        Map<String, String> relocationExcludes = getRelocationExcludes().getOrElse(Collections.emptyMap());

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

        // --- Parallel phase: one worker per source on Gradle's pool (--max-workers) ---
        WorkQueue queue = getWorkerExecutor().noIsolation();
        List<File> parts = new ArrayList<>(sources.size());
        for (int i = 0; i < sources.size(); i++) {
            final File src = sources.get(i);
            final File part = new File(partsDir, i + ".part");
            parts.add(part);
            queue.submit(PackAction.class, p -> {
                p.getSource().set(src);
                p.getPart().set(part);
                p.getLevel().set(level);
                p.getStore().set(store);
                p.getRelocations().set(relocations);
                p.getRelocationIncludes().set(relocationIncludes);
                p.getRelocationExcludes().set(relocationExcludes);
            });
        }
        queue.await();
        long tPack = System.nanoTime();

        // --- Sequential phase: assemble parts into one jar ---
        Assembly a = assemble(parts, outFile);
        long tEnd = System.nanoTime();

        deleteRecursively(partsDir);
        getLogger().lifecycle(String.format(
                "shaded-jar: %d sources -> %d entries, %.1f MiB  (dropped %d dup/filtered)  "
                        + "pack=%.0fms assemble=%.0fms TOTAL=%.0fms",
                sources.size(), a.entryCount, a.archiveSize / 1048576.0, a.dropped,
                (tPack - t0) / 1e6, (tEnd - tPack) / 1e6, (tEnd - t0) / 1e6));
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
        // Spring properties files are merged per-key (see SpringProperties): file name -> key -> values.
        Map<String, Map<String, LinkedHashSet<String>>> springFiles = new LinkedHashMap<>();
        long[] offset = {0};

        // Known upfront (before the main copy pass) so the manifest — written first,
        // matching the usual jar convention — can carry Multi-Release: true if needed.
        boolean multiRelease = anyMultiReleaseEntries(parts);

        try (OutputStream raw = Files.newOutputStream(outFile.toPath());
             OutputStream os = new BufferedOutputStream(raw, 1 << 20)) {

            // Our generated manifest always goes first and wins.
            byte[] manifestBytes = buildManifest(multiRelease);
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
                        // Same idea for the well-known Spring properties files, per-key.
                        SpringProperties.Kind springKind = SpringProperties.Kind.of(name);
                        if (springKind != null) {
                            byte[] body = readEntryBytes(din, method, compSize, rawSize);
                            Map<String, LinkedHashSet<String>> keyValues =
                                    springFiles.computeIfAbsent(name, k -> new LinkedHashMap<>());
                            for (String conflict : SpringProperties.accumulate(keyValues, springKind, body)) {
                                getLogger().warn("shaded-jar: " + conflict);
                            }
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

            // Emit the merged Spring properties files (spring.factories/.handlers/.schemas).
            List<String> springNames = new ArrayList<>(springFiles.keySet());
            Collections.sort(springNames);
            for (String name : springNames) {
                if (!seen.add(name)) continue;
                writeStored(os, name, SpringProperties.renderMerged(springFiles.get(name)), central, offset);
            }

            long cdOffset = offset[0];
            byte[] cd = buildCentralDirectory(central, cdOffset);
            os.write(cd);
            a.archiveSize = cdOffset + cd.length;
        }

        a.entryCount = central.size();
        return a;
    }

    /**
     * Whether any source contributes at least one {@code META-INF/versions/N/...}
     * entry — i.e. whether the merged output needs {@code Multi-Release: true} in
     * its manifest for the JVM to look at that directory at all. A cheap
     * name-only pre-pass over the part files (reading headers, skipping payload
     * bytes) since the manifest — written first, per convention — needs this
     * decided before the real copy pass sees any entries.
     */
    private static boolean anyMultiReleaseEntries(List<File> parts) throws IOException {
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
                        break;
                    }
                    din.readInt();  // method
                    din.readLong(); // crc
                    long compSize = din.readLong();
                    din.readLong(); // rawSize
                    if (MRJAR_VERSIONS.matcher(name).matches()) return true;
                    skipFully(din, compSize);
                }
            }
        }
        return false;
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

    private byte[] buildManifest(boolean multiRelease) throws IOException {
        Manifest manifest = new Manifest();
        Attributes attrs = manifest.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (getMainClass().isPresent() && !getMainClass().get().isEmpty()) {
            attrs.put(Attributes.Name.MAIN_CLASS, getMainClass().get());
        }
        if (multiRelease) {
            // Built by hand (not Attributes.Name.MULTI_RELEASE, a JDK 9+-only constant)
            // since the plugin targets Java 8 bytecode and may run under an older JDK.
            attrs.put(new Attributes.Name("Multi-Release"), "true");
        }
        if (getManifestAttributes().isPresent()) {
            for (Map.Entry<String, String> e : getManifestAttributes().get().entrySet()) {
                attrs.put(new Attributes.Name(e.getKey()), e.getValue());
            }
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
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

    /**
     * Writes a local file header, promoting to a Zip64 extra field (and
     * sentinel {@code 0xFFFFFFFF} size fields) when {@code compSize} or
     * {@code rawSize} doesn't fit in 32 bits. See {@link Zip64Support}.
     */
    private long writeLocalHeader(OutputStream os, byte[] nameBytes, int method,
                                  long crc, long compSize, long rawSize) throws IOException {
        boolean zip64 = Zip64Support.needsZip64(compSize, rawSize);
        byte[] extra = zip64 ? Zip64Support.localExtra(rawSize, compSize) : Zip64Support.NO_EXTRA;
        ByteBuffer b = ByteBuffer.allocate(30 + nameBytes.length + extra.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(PartFormat.LFH_SIG);
        b.putShort((short) (zip64 ? 45 : 20));
        b.putShort((short) PartFormat.FLAG_UTF8);
        b.putShort((short) method);
        b.putShort((short) PartFormat.DOS_TIME);
        b.putShort((short) PartFormat.DOS_DATE);
        b.putInt((int) crc);
        b.putInt(zip64 ? 0xFFFFFFFF : (int) compSize);
        b.putInt(zip64 ? 0xFFFFFFFF : (int) rawSize);
        b.putShort((short) nameBytes.length);
        b.putShort((short) extra.length);
        b.put(nameBytes);
        b.put(extra);
        os.write(b.array());
        return b.array().length;
    }

    /**
     * Builds the central directory plus EOCD, promoting to a Zip64 End Of
     * Central Directory record + locator (preceding the classic EOCD, whose
     * count/size/offset fields are then all sentineled together) when there
     * are more than 65,534 entries, or the central directory's own size or
     * starting offset doesn't fit in 32 bits. See {@link Zip64Support}.
     */
    private byte[] buildCentralDirectory(List<CdEntry> central, long cdOffset) {
        ByteArrayOutputStream cdBody = new ByteArrayOutputStream(central.size() * 64);
        for (CdEntry e : central) {
            writeCentralDirectoryRecord(cdBody, e);
        }
        byte[] cdBytes = cdBody.toByteArray();
        long cdSize = cdBytes.length;
        int entryCount = central.size();
        boolean zip64 = entryCount > Zip64Support.MAX_STANDARD_ENTRIES
                || Zip64Support.needsZip64(cdSize, cdOffset);

        ByteArrayOutputStream out = new ByteArrayOutputStream(cdBytes.length + (zip64 ? 76 : 22));
        out.write(cdBytes, 0, cdBytes.length);
        if (zip64) {
            byte[] eocd64 = Zip64Support.eocdRecord(entryCount, cdSize, cdOffset);
            out.write(eocd64, 0, eocd64.length);
            byte[] locator = Zip64Support.locatorRecord(cdOffset + cdSize);
            out.write(locator, 0, locator.length);
        }
        writeClassicEocd(out, entryCount, cdSize, cdOffset, zip64);
        return out.toByteArray();
    }

    private static void writeCentralDirectoryRecord(ByteArrayOutputStream body, CdEntry e) {
        boolean zip64 = Zip64Support.needsZip64(e.compSize, e.rawSize, e.offset);
        byte[] extra = zip64 ? Zip64Support.centralExtra(e.rawSize, e.compSize, e.offset) : Zip64Support.NO_EXTRA;
        ByteBuffer b = ByteBuffer.allocate(46 + e.name.length + extra.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(PartFormat.CDH_SIG);
        b.putShort((short) (zip64 ? 45 : 20));
        b.putShort((short) (zip64 ? 45 : 20));
        b.putShort((short) PartFormat.FLAG_UTF8);
        b.putShort((short) e.method);
        b.putShort((short) PartFormat.DOS_TIME);
        b.putShort((short) PartFormat.DOS_DATE);
        b.putInt((int) e.crc);
        b.putInt(zip64 ? 0xFFFFFFFF : (int) e.compSize);
        b.putInt(zip64 ? 0xFFFFFFFF : (int) e.rawSize);
        b.putShort((short) e.name.length);
        b.putShort((short) extra.length);
        b.putShort((short) 0); // comment length
        b.putShort((short) 0); // disk number start
        b.putShort((short) 0); // internal file attributes
        b.putInt(0);           // external file attributes
        b.putInt(zip64 ? 0xFFFFFFFF : (int) e.offset);
        b.put(e.name);
        b.put(extra);
        body.write(b.array(), 0, b.array().length);
    }

    /** Classic EOCD; when {@code zip64}, its count/size/offset fields are all sentineled together. */
    private static void writeClassicEocd(ByteArrayOutputStream out, int entryCount, long cdSize,
                                         long cdOffset, boolean zip64) {
        int countField = zip64 ? 0xFFFF : entryCount;
        long sizeField = zip64 ? 0xFFFFFFFFL : cdSize;
        long offsetField = zip64 ? 0xFFFFFFFFL : cdOffset;
        ByteBuffer b = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(PartFormat.EOCD_SIG);
        b.putShort((short) 0);
        b.putShort((short) 0);
        b.putShort((short) countField);
        b.putShort((short) countField);
        b.putInt((int) sizeField);
        b.putInt((int) offsetField);
        b.putShort((short) 0);
        out.write(b.array(), 0, b.array().length);
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
