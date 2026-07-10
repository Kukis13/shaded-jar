package com.ljarocki.shadedjar;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end proof that the Zip64 entry-count path in {@code FatJarTask}
 * actually produces a valid, readable archive, by feeding it a dependency jar
 * with more entries than the classic ZIP format's 65,534-entry ceiling.
 *
 * <p>The fixture jar is built with a single {@link ZipOutputStream} rather than
 * one file per entry on disk — 66,000 individual file creates turned out to be
 * the dominant cost here (multiple minutes on Windows/NTFS in practice), while
 * writing 66,000 tiny zip entries into one file is well under a second.
 *
 * <p>This deliberately doesn't try to exercise the &gt;4 GiB size/offset Zip64
 * paths end-to-end (impractical in a test); those are covered at the byte-layout
 * level by {@link Zip64SupportTest} instead.
 */
class Zip64EntryCountFunctionalTest {

    @TempDir
    Path dir;

    private static final int TOTAL_ENTRIES = 66_000; // > 65,534

    @Test
    void moreThan65534Entries_stillProducesAValidReadableJar() throws Exception {
        Path depsDir = dir.resolve("libs");
        Files.createDirectories(depsDir);
        Path manyEntriesJar = depsDir.resolve("many-entries.jar");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(manyEntriesJar.toFile()))) {
            for (int i = 0; i < TOTAL_ENTRIES; i++) {
                zos.putNextEntry(new ZipEntry("d/" + i + ".txt"));
                zos.write(("entry " + i).getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }

        Files.write(dir.resolve("settings.gradle"), "rootProject.name = 'app'\n".getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("build.gradle"), (
                "plugins {\n"
                + "  id 'java'\n"
                + "  id 'com.ljarocki.shaded-jar'\n"
                + "}\n"
                + "dependencies {\n"
                + "  implementation files('libs/many-entries.jar')\n"
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

        Path jar = dir.resolve("build/libs/app-all.jar");
        assertTrue(Files.exists(jar), "archive produced");

        int count = 0;
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                count++;
                if (e.getName().equals("d/0.txt") || e.getName().equals("d/65999.txt")) {
                    byte[] content = zf.getInputStream(e).readAllBytes();
                    assertTrue(new String(content, StandardCharsets.UTF_8).startsWith("entry "), e.getName());
                }
            }
        }
        // +1 for our generated META-INF/MANIFEST.MF.
        assertEquals(TOTAL_ENTRIES + 1, count, "java.util.zip.ZipFile reads back every entry via the Zip64 EOCD");
    }
}
