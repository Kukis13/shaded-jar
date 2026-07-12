package com.ljarocki.shadedjar;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Hermetic tests for {@link PackCache} — no Gradle, real filesystem via {@code @TempDir}. */
class PackCacheTest {

    @TempDir
    Path dir;

    private static Map<String, String> map(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    // --- configFingerprint --------------------------------------------------

    @Test
    void fingerprint_isStableForIdenticalConfig_regardlessOfMapInsertionOrder() {
        Map<String, String> a = map("com.a", "shaded.a", "com.b", "shaded.b");
        Map<String, String> b = map("com.b", "shaded.b", "com.a", "shaded.a"); // reversed insertion order
        byte[] fp1 = PackCache.configFingerprint(6, false, a, Collections.emptyMap(), Collections.emptyMap());
        byte[] fp2 = PackCache.configFingerprint(6, false, b, Collections.emptyMap(), Collections.emptyMap());
        assertArrayEquals(fp1, fp2);
    }

    @Test
    void fingerprint_changesWithEveryParameterThatAffectsOutput() {
        byte[] base = PackCache.configFingerprint(6, false, map("a", "b"), Collections.emptyMap(), Collections.emptyMap());

        assertNotEquals(bytesToHex(base), bytesToHex(
                PackCache.configFingerprint(9, false, map("a", "b"), Collections.emptyMap(), Collections.emptyMap())),
                "level must affect the fingerprint");
        assertNotEquals(bytesToHex(base), bytesToHex(
                PackCache.configFingerprint(6, true, map("a", "b"), Collections.emptyMap(), Collections.emptyMap())),
                "store must affect the fingerprint");
        assertNotEquals(bytesToHex(base), bytesToHex(
                PackCache.configFingerprint(6, false, map("a", "c"), Collections.emptyMap(), Collections.emptyMap())),
                "a relocation target change must affect the fingerprint");
        assertNotEquals(bytesToHex(base), bytesToHex(
                PackCache.configFingerprint(6, false, map("a", "b"), map("a", "a.x.**"), Collections.emptyMap())),
                "adding an include pattern must affect the fingerprint");
        assertNotEquals(bytesToHex(base), bytesToHex(
                PackCache.configFingerprint(6, false, map("a", "b"), Collections.emptyMap(), map("a", "a.y.**"))),
                "adding an exclude pattern must affect the fingerprint");
    }

    // --- cacheKey -------------------------------------------------------------

    @Test
    void cacheKey_isContentBased_notPathBased() throws Exception {
        byte[] fp = PackCache.configFingerprint(-1, false, Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap());
        File fileA = writeFile("a.jar", "identical content");
        File fileB = writeFile("subdir/b.jar", "identical content");
        assertEquals(PackCache.cacheKey(fp, fileA), PackCache.cacheKey(fp, fileB),
                "same bytes, different path/name -> same key");
    }

    @Test
    void cacheKey_changesWithSourceContentAndWithConfig() throws Exception {
        File file = writeFile("dep.jar", "v1 content");
        byte[] fp1 = PackCache.configFingerprint(-1, false, Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap());
        byte[] fp2 = PackCache.configFingerprint(9, false, Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap());
        String keyBase = PackCache.cacheKey(fp1, file);

        assertNotEquals(keyBase, PackCache.cacheKey(fp2, file), "different config -> different key, same file");

        File changed = writeFile("dep.jar", "v2 content"); // overwrite same path with different content
        assertNotEquals(keyBase, PackCache.cacheKey(fp1, changed), "different content -> different key, same path");
    }

    // --- store / find round trip -----------------------------------------------

    @Test
    void storeThenFind_roundTripsContentAndTouchesMtimeOnHit() throws Exception {
        File cacheDir = dir.resolve("cache").toFile();
        File freshPart = writeFile("fresh.part", "packed bytes go here");

        assertNull(PackCache.find(cacheDir, "some-key"), "miss before anything is stored");

        PackCache.store(cacheDir, "some-key", freshPart);
        File cached = PackCache.find(cacheDir, "some-key");
        assertNotNull(cached);
        assertArrayEquals(Files.readAllBytes(freshPart.toPath()), Files.readAllBytes(cached.toPath()));

        // Age the entry artificially, then confirm a hit refreshes its mtime (for LRU eviction ordering).
        Files.setLastModifiedTime(cached.toPath(), FileTime.from(Instant.EPOCH));
        long agedMtime = cached.lastModified();
        PackCache.find(cacheDir, "some-key");
        assertTrue(cached.lastModified() > agedMtime, "a cache hit must refresh the entry's mtime");
    }

    @Test
    void store_concurrentWriteOfTheSameKey_doesNotFailAndLeavesValidContent() throws Exception {
        File cacheDir = dir.resolve("cache").toFile();
        File freshPart = writeFile("fresh.part", "same packed bytes either writer would produce");

        PackCache.store(cacheDir, "race-key", freshPart);
        PackCache.store(cacheDir, "race-key", freshPart); // simulates a second daemon losing the race, harmlessly

        File cached = PackCache.find(cacheDir, "race-key");
        assertNotNull(cached);
        assertArrayEquals(Files.readAllBytes(freshPart.toPath()), Files.readAllBytes(cached.toPath()));
        // No leftover .tmp- files from either write.
        File[] leftovers = cacheDir.listFiles((d, name) -> name.contains(".tmp-"));
        assertTrue(leftovers == null || leftovers.length == 0);
    }

    // --- evictIfNeeded ----------------------------------------------------------

    @Test
    void evictIfNeeded_isANoOpWhenUnderBudget() throws Exception {
        File cacheDir = dir.resolve("cache").toFile();
        PackCache.store(cacheDir, "only-key", writeFile("p.part", "small"));
        PackCache.evictIfNeeded(cacheDir, 10_000_000L);
        assertNotNull(PackCache.find(cacheDir, "only-key"));
    }

    @Test
    void evictIfNeeded_removesLeastRecentlyUsedEntriesUntilUnderBudget() throws Exception {
        File cacheDir = dir.resolve("cache").toFile();
        cacheDir.mkdirs();

        // Three ~100-byte entries, oldest to newest by mtime.
        String content = repeat("x", 100);
        writeCacheEntry(cacheDir, "oldest", content, Instant.now().minusSeconds(300));
        writeCacheEntry(cacheDir, "middle", content, Instant.now().minusSeconds(200));
        writeCacheEntry(cacheDir, "newest", content, Instant.now().minusSeconds(100));

        // Budget for only one entry: must keep "newest", drop the other two.
        PackCache.evictIfNeeded(cacheDir, 150L);

        assertNull(PackCache.find(cacheDir, "oldest"));
        assertNull(PackCache.find(cacheDir, "middle"));
        assertNotNull(PackCache.find(cacheDir, "newest"));
    }

    // --- helpers ----------------------------------------------------------------

    private File writeFile(String relativePath, String content) throws Exception {
        Path p = dir.resolve(relativePath);
        Files.createDirectories(p.getParent());
        Files.write(p, content.getBytes(StandardCharsets.UTF_8));
        return p.toFile();
    }

    private static void writeCacheEntry(File cacheDir, String key, String content, Instant mtime) throws Exception {
        File f = new File(cacheDir, key + ".part");
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(f.toPath(), FileTime.from(mtime));
    }

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    private static String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
