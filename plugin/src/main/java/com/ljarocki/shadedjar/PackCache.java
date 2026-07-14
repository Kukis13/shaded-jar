package com.ljarocki.shadedjar;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A persistent, cross-build cache of packed "part" files (see {@link PartFormat}),
 * so an unchanged dependency jar doesn't have to be re-inflated/ASM-rewritten/
 * re-deflated on every build — only the sources that actually changed since the
 * last successful pack do. Lives under Gradle's user home (see {@code
 * ShadedJarPlugin}), not the project's own {@code build/} directory, specifically
 * so it survives across fresh checkouts when CI restores {@code ~/.gradle/caches}
 * (which most Gradle CI setups already do for the dependency cache) — a
 * project-scoped cache would only ever help the same local checkout.
 *
 * <p>Deliberately only used for {@code .isFile()} sources (dependency jars), never
 * directories (a project's own compiled output): those change on essentially
 * every build in the scenario this exists to speed up, so caching them buys
 * nothing and just adds hashing overhead. See {@code PackAction}.
 *
 * <p>The cache key hashes the source file's actual bytes together with every
 * packing parameter that affects the output ({@link #configFingerprint}) — level,
 * store, and all three relocation maps — so a config change (e.g. adding a
 * relocation rule) can never silently serve a stale, wrongly-packaged entry; it
 * just naturally misses and repacks. Entries are written via a temp-file-then-
 * atomic-rename so concurrent Gradle daemons (different projects, or CI agents
 * sharing a cache restore) can never observe a partially-written entry.
 */
final class PackCache {
    private PackCache() {}

    /** Cache format version, folded into the directory path (see {@code ShadedJarPlugin}) — bump on any incompatible change to what gets stored. */
    static final String SCHEMA_VERSION = "v1";

    /** Default total cache size cap before {@link #evictIfNeeded} starts pruning the least-recently-used entries. */
    static final long DEFAULT_MAX_BYTES = 1L << 30; // 1 GiB

    /**
     * Canonical, order-independent fingerprint of every packing parameter that
     * affects a source's output bytes — including {@code dropClasses} (see
     * {@code Minimizer}): a source's own bytes and every other parameter can be
     * unchanged while a classpath change *elsewhere* alters which of its classes
     * {@code minimize()} would drop, so that decision must be part of the key
     * too, or a cache hit could silently serve a stale, wrongly-minimized part.
     */
    static byte[] configFingerprint(int level, boolean store, Map<String, String> relocations,
                                    Map<String, String> relocationIncludes, Map<String, String> relocationExcludes,
                                    Set<String> dropClasses) {
        StringBuilder sb = new StringBuilder();
        sb.append("level=").append(level).append(';');
        sb.append("store=").append(store).append(';');
        appendSortedMap(sb, "relocations", relocations);
        appendSortedMap(sb, "includes", relocationIncludes);
        appendSortedMap(sb, "excludes", relocationExcludes);
        appendSortedSet(sb, "dropClasses", dropClasses);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendSortedMap(StringBuilder sb, String label, Map<String, String> map) {
        sb.append(label).append('=');
        List<String> keys = new ArrayList<>(map.keySet());
        Collections.sort(keys);
        for (String k : keys) {
            sb.append(k).append("->").append(map.get(k)).append(',');
        }
        sb.append(';');
    }

    private static void appendSortedSet(StringBuilder sb, String label, Set<String> set) {
        sb.append(label).append('=');
        List<String> sorted = new ArrayList<>(set);
        Collections.sort(sorted);
        for (String s : sorted) {
            sb.append(s).append(',');
        }
        sb.append(';');
    }

    /** SHA-256 of {@code configFingerprint} followed by the source file's full content, hex-encoded. */
    static String cacheKey(byte[] configFingerprint, File sourceFile) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IOException("SHA-256 not available", ex);
        }
        digest.update(configFingerprint);
        byte[] buf = new byte[1 << 16];
        try (InputStream in = new BufferedInputStream(Files.newInputStream(sourceFile.toPath()), buf.length)) {
            int n;
            while ((n = in.read(buf)) >= 0) digest.update(buf, 0, n);
        }
        byte[] hash = digest.digest();
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    /**
     * The cached part file for {@code key}, or {@code null} on a miss. Touches the
     * entry's last-modified time on a hit, so {@link #evictIfNeeded} evicts by
     * actual recency of use, not just recency of first being written.
     */
    static File find(File cacheDir, String key) {
        File f = entryFile(cacheDir, key);
        if (!f.isFile()) return null;
        try {
            Files.setLastModifiedTime(f.toPath(), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
        } catch (IOException ignored) {
            // Touching the mtime is a pure eviction-ordering nicety; a cache hit is still a cache hit without it.
        }
        return f;
    }

    /** Store {@code freshPart} under {@code key}, atomically — safe against concurrent writers racing the same key. */
    static void store(File cacheDir, String key, File freshPart) throws IOException {
        cacheDir.mkdirs();
        File dest = entryFile(cacheDir, key);
        File tmp = new File(cacheDir, key + ".part.tmp-" + UUID.randomUUID());
        Files.copy(freshPart.toPath(), tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
        try {
            try {
                Files.move(tmp.toPath(), dest.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException notSupported) {
                Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (FileAlreadyExistsException raceLostToAnotherWriter) {
            // Another process/daemon wrote the same key concurrently — its content is
            // equally valid (same key = same source + config), so just drop ours.
            Files.deleteIfExists(tmp.toPath());
        }
    }

    private static File entryFile(File cacheDir, String key) {
        return new File(cacheDir, key + ".part");
    }

    /**
     * Deletes least-recently-used entries (by file mtime — see {@link #find})
     * until the cache's total size is at or under {@code maxBytes}. A no-op if
     * the directory doesn't exist yet or is already within budget.
     */
    static void evictIfNeeded(File cacheDir, long maxBytes) {
        File[] files = cacheDir.listFiles((dir, name) -> name.endsWith(".part"));
        if (files == null || files.length == 0) return;

        long total = 0;
        for (File f : files) total += f.length();
        if (total <= maxBytes) return;

        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (File f : files) {
            if (total <= maxBytes) break;
            long len = f.length();
            if (f.delete()) total -= len;
        }
    }
}
