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
import java.util.Arrays;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end proof that {@code spring.factories}/{@code .handlers} merging and
 * relocation work through the real {@code fatJar} task — not just at the
 * {@link SpringPropertiesTest} unit level — by bundling two small hand-built
 * dependency jars (no network) that each contribute conflicting/overlapping
 * entries.
 */
class SpringPropertiesFunctionalTest {

    @TempDir
    Path dir;

    private void writeDepJar(String path, String... nameThenContent) throws Exception {
        Path jar = dir.resolve(path);
        Files.createDirectories(jar.getParent());
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(jar.toFile()))) {
            for (int i = 0; i < nameThenContent.length; i += 2) {
                zos.putNextEntry(new ZipEntry(nameThenContent[i]));
                zos.write(nameThenContent[i + 1].getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
    }

    private BuildResult run(String buildScript) throws Exception {
        Files.write(dir.resolve("settings.gradle"), "rootProject.name = 'app'\n".getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("build.gradle"), buildScript.getBytes(StandardCharsets.UTF_8));
        return GradleRunner.create()
                .withProjectDir(dir.toFile())
                .withPluginClasspath()
                .withArguments("fatJar", "--stacktrace")
                .build();
    }

    @Test
    void springFactoriesFromMultipleJars_areMergedByKey_notFirstWins() throws Exception {
        writeDepJar("libs/dep-a.jar",
                "META-INF/spring.factories", "org.example.Ext=com.example.a.One\n");
        writeDepJar("libs/dep-b.jar",
                "META-INF/spring.factories",
                "org.example.Ext=com.example.b.Two\norg.example.Other=com.example.b.Three\n");

        BuildResult result = run(
                "plugins {\n"
                + "  id 'java'\n"
                + "  id 'com.ljarocki.shaded-jar'\n"
                + "}\n"
                + "dependencies {\n"
                + "  implementation files('libs/dep-a.jar', 'libs/dep-b.jar')\n"
                + "}\n"
                + "shadedJar { archiveClassifier = 'all' }\n");
        assertEquals(TaskOutcome.SUCCESS, result.task(":fatJar").getOutcome());

        Properties merged = readEntryAsProperties(dir.resolve("build/libs/app-all.jar"), "META-INF/spring.factories");
        assertEquals(commaSet("com.example.a.One,com.example.b.Two"), commaSet(merged.getProperty("org.example.Ext")));
        assertEquals("com.example.b.Three", merged.getProperty("org.example.Other"));
    }

    @Test
    void springFactoriesEntry_isRelocatedWhenItsPackageIsRelocated() throws Exception {
        writeDepJar("libs/dep-a.jar",
                "META-INF/spring.factories",
                "com.example.a.Ext=com.example.a.Impl\n");

        BuildResult result = run(
                "plugins {\n"
                + "  id 'java'\n"
                + "  id 'com.ljarocki.shaded-jar'\n"
                + "}\n"
                + "dependencies {\n"
                + "  implementation files('libs/dep-a.jar')\n"
                + "}\n"
                + "shadedJar {\n"
                + "  archiveClassifier = 'all'\n"
                + "  relocate 'com.example.a', 'com.example.shaded.a'\n"
                + "}\n");
        assertEquals(TaskOutcome.SUCCESS, result.task(":fatJar").getOutcome());

        Properties out = readEntryAsProperties(dir.resolve("build/libs/app-all.jar"), "META-INF/spring.factories");
        assertEquals(1, out.size());
        assertEquals("com.example.shaded.a.Impl", out.getProperty("com.example.shaded.a.Ext"));
    }

    @Test
    void conflictingSpringHandlersKey_isKeptFirstWinsAndWarnedAbout() throws Exception {
        writeDepJar("libs/dep-a.jar",
                "META-INF/spring.handlers", "http\\://example.com/ns=com.example.a.HandlerA\n");
        writeDepJar("libs/dep-b.jar",
                "META-INF/spring.handlers", "http\\://example.com/ns=com.example.b.HandlerB\n");

        BuildResult result = run(
                "plugins {\n"
                + "  id 'java'\n"
                + "  id 'com.ljarocki.shaded-jar'\n"
                + "}\n"
                + "dependencies {\n"
                + "  implementation files('libs/dep-a.jar', 'libs/dep-b.jar')\n"
                + "}\n"
                + "shadedJar { archiveClassifier = 'all' }\n");
        assertEquals(TaskOutcome.SUCCESS, result.task(":fatJar").getOutcome());

        // The build log surfaces the conflict instead of silently dropping one side.
        assertTrue(result.getOutput().contains("http://example.com/ns"), result.getOutput());
        assertTrue(result.getOutput().contains("com.example.a.HandlerA"), result.getOutput());
        assertTrue(result.getOutput().contains("com.example.b.HandlerB"), result.getOutput());

        // Exactly one of the two conflicting values survives — not both comma-joined.
        Properties out = readEntryAsProperties(dir.resolve("build/libs/app-all.jar"), "META-INF/spring.handlers");
        String kept = out.getProperty("http://example.com/ns");
        assertTrue(kept.equals("com.example.a.HandlerA") || kept.equals("com.example.b.HandlerB"), kept);
    }

    // --- helpers --------------------------------------------------------------

    private static Properties readEntryAsProperties(Path jar, String name) throws Exception {
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            ZipEntry e = zf.getEntry(name);
            Properties props = new Properties();
            props.load(zf.getInputStream(e));
            return props;
        }
    }

    private static Set<String> commaSet(String value) {
        return new HashSet<>(Arrays.asList(value.split(",")));
    }
}
