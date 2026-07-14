package com.ljarocki.shadedjar;

import org.apache.logging.log4j.core.config.plugins.processor.PluginCache;
import org.apache.logging.log4j.core.config.plugins.processor.PluginEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Hermetic tests for {@link Log4j2PluginCacheMerger} — real {@code PluginCache}/
 * {@code PluginEntry} objects (log4j-core is already a dependency), no Gradle,
 * no network, no real annotation-processor run.
 */
class Log4j2PluginCacheMergerTest {

    private static byte[] cacheWith(String category, String key, String className) throws Exception {
        PluginEntry entry = new PluginEntry();
        entry.setKey(key);
        entry.setClassName(className);
        entry.setName(key);
        entry.setCategory(category);
        entry.setPrintable(false);
        entry.setDefer(false);

        PluginCache cache = new PluginCache();
        cache.getCategory(category).put(entry.getKey(), entry);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        cache.writeCache(bos);
        return bos.toByteArray();
    }

    private static PluginCache load(byte[] merged, File tempDir) throws Exception {
        File f = new File(tempDir, "reloaded.dat");
        Files.write(f.toPath(), merged);
        PluginCache cache = new PluginCache();
        cache.loadCacheFiles(Collections.enumeration(Collections.singletonList(f.toURI().toURL())));
        return cache;
    }

    @Test
    void merge_combinesEntriesFromBothSources(@TempDir File tmp) throws Exception {
        byte[] a = cacheWith("core", "one", "com.example.dep.One");
        byte[] b = cacheWith("core", "two", "com.example.other.Two");

        byte[] merged = Log4j2PluginCacheMerger.merge(Arrays.asList(a, b), new File(tmp, "scratch"),
                new Relocator(Collections.emptyMap()));

        PluginCache reloaded = load(merged, tmp);
        Map<String, PluginEntry> core = reloaded.getAllCategories().get("core");
        assertNotNull(core);
        assertEquals("com.example.dep.One", core.get("one").getClassName());
        assertEquals("com.example.other.Two", core.get("two").getClassName());
    }

    @Test
    void merge_relocatesEachEntrysClassNameOnceAsAWhole(@TempDir File tmp) throws Exception {
        byte[] a = cacheWith("core", "one", "com.example.dep.One");
        byte[] b = cacheWith("core", "two", "com.example.other.Two");

        Relocator relocator = new Relocator(Collections.singletonMap("com.example.dep", "shaded.dep"));
        byte[] merged = Log4j2PluginCacheMerger.merge(Arrays.asList(a, b), new File(tmp, "scratch"), relocator);

        PluginCache reloaded = load(merged, tmp);
        Map<String, PluginEntry> core = reloaded.getAllCategories().get("core");
        assertEquals("shaded.dep.One", core.get("one").getClassName(), "relocated plugin's class name is rewritten");
        assertEquals("com.example.other.Two", core.get("two").getClassName(), "unrelated plugin is left alone");
    }

    @Test
    void merge_ofNoSources_producesAnEmptyButValidCache(@TempDir File tmp) throws Exception {
        byte[] merged = Log4j2PluginCacheMerger.merge(Collections.emptyList(), new File(tmp, "scratch"),
                new Relocator(Collections.emptyMap()));

        PluginCache reloaded = load(merged, tmp);
        assertEquals(0, reloaded.size());
        assertNull(reloaded.getAllCategories().get("core"));
    }
}
