package com.ljarocki.shadedjar;

import org.apache.logging.log4j.core.config.plugins.processor.PluginCache;
import org.apache.logging.log4j.core.config.plugins.processor.PluginEntry;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * End-to-end proof that two dependencies' {@code Log4j2Plugins.dat} files are
 * genuinely merged (not first-wins) through the real {@code fatJar} task —
 * not just at the {@link Log4j2PluginCacheMergerTest} unit level — using two
 * hand-built dependency jars (no network, no real annotation-processor run).
 */
class Log4j2PluginCacheFunctionalTest {

    @TempDir
    Path dir;

    private static byte[] pluginCacheBytes(String key, String className) throws Exception {
        PluginEntry entry = new PluginEntry();
        entry.setKey(key);
        entry.setClassName(className);
        entry.setName(key);
        entry.setCategory("core");
        entry.setPrintable(false);
        entry.setDefer(false);

        PluginCache cache = new PluginCache();
        cache.getCategory("core").put(entry.getKey(), entry);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        cache.writeCache(bos);
        return bos.toByteArray();
    }

    private void writeDependencyJar(String relativePath, String pluginKey, String pluginClassName) throws Exception {
        Path jar = dir.resolve(relativePath);
        Files.createDirectories(jar.getParent());
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(jar.toFile()))) {
            zos.putNextEntry(new ZipEntry(Log4j2PluginCacheMerger.ENTRY_NAME));
            zos.write(pluginCacheBytes(pluginKey, pluginClassName));
            zos.closeEntry();
        }
    }

    @Test
    void fatJar_mergesBothDependenciesPluginCaches_notFirstWins() throws Exception {
        writeDependencyJar("libs/one.jar", "one", "com.example.dep.One");
        writeDependencyJar("libs/two.jar", "two", "com.example.dep.Two");

        Files.write(dir.resolve("settings.gradle"), "rootProject.name = 'app'\n".getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("build.gradle"), (
                "plugins {\n"
                + "  id 'java'\n"
                + "  id 'com.ljarocki.shaded-jar'\n"
                + "}\n"
                + "dependencies {\n"
                + "  implementation files('libs/one.jar', 'libs/two.jar')\n"
                + "}\n"
                + "shadedJar {\n"
                + "  archiveClassifier = 'all'\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));

        BuildResult result = GradleRunner.create()
                .withProjectDir(dir.toFile())
                .withPluginClasspath()
                .withArguments("fatJar", "--stacktrace")
                .build();
        assertEquals(TaskOutcome.SUCCESS, result.task(":fatJar").getOutcome());

        Map<String, PluginEntry> core = loadMergedCore(dir.resolve("build/libs/app-all.jar"));
        assertNotNull(core.get("one"), "first dependency's plugin entry survived the merge");
        assertNotNull(core.get("two"), "second dependency's plugin entry survived the merge (not first-wins)");
        assertEquals("com.example.dep.One", core.get("one").getClassName());
        assertEquals("com.example.dep.Two", core.get("two").getClassName());
    }

    @Test
    void fatJar_relocatesMergedPluginClassNames() throws Exception {
        writeDependencyJar("libs/one.jar", "one", "com.example.dep.One");
        writeDependencyJar("libs/two.jar", "two", "com.example.dep.Two");

        Files.write(dir.resolve("settings.gradle"), "rootProject.name = 'app'\n".getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("build.gradle"), (
                "plugins {\n"
                + "  id 'java'\n"
                + "  id 'com.ljarocki.shaded-jar'\n"
                + "}\n"
                + "dependencies {\n"
                + "  implementation files('libs/one.jar', 'libs/two.jar')\n"
                + "}\n"
                + "shadedJar {\n"
                + "  archiveClassifier = 'all'\n"
                + "  relocate 'com.example.dep', 'shaded.dep'\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));

        BuildResult result = GradleRunner.create()
                .withProjectDir(dir.toFile())
                .withPluginClasspath()
                .withArguments("fatJar", "--stacktrace")
                .build();
        assertEquals(TaskOutcome.SUCCESS, result.task(":fatJar").getOutcome());

        Map<String, PluginEntry> core = loadMergedCore(dir.resolve("build/libs/app-all.jar"));
        assertEquals("shaded.dep.One", core.get("one").getClassName());
        assertEquals("shaded.dep.Two", core.get("two").getClassName());
    }

    // --- helpers --------------------------------------------------------------

    private static Map<String, PluginEntry> loadMergedCore(Path jarPath) throws Exception {
        byte[] entryBytes;
        try (ZipFile zf = new ZipFile(jarPath.toFile())) {
            ZipEntry entry = zf.getEntry(Log4j2PluginCacheMerger.ENTRY_NAME);
            assertNotNull(entry, "merged Log4j2Plugins.dat is present in the fat jar");
            entryBytes = zf.getInputStream(entry).readAllBytes();
        }
        Path tempCopy = Files.createTempFile("merged-log4j2-plugins", ".dat");
        Files.write(tempCopy, entryBytes);
        PluginCache cache = new PluginCache();
        cache.loadCacheFiles(Collections.enumeration(Collections.singletonList(tempCopy.toUri().toURL())));
        return cache.getAllCategories().get("core");
    }
}
