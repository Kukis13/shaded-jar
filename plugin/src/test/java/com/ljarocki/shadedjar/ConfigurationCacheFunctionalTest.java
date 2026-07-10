package com.ljarocki.shadedjar;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves {@code fatJar} is actually compatible with Gradle's configuration
 * cache — not just "no obvious problems seen once" but the real store-then-reuse
 * cycle — on both Gradle major versions the plugin claims to support.
 *
 * <p>The task reads a resolved {@code Configuration} and a {@code Project}-derived
 * output-file-name {@code Provider} at execution time (see {@code
 * ShadedJarPlugin.archiveName}), both classic ways a task can accidentally
 * become configuration-cache-incompatible by capturing {@code Project} itself
 * rather than the plain values it needs — so this is worth verifying directly
 * rather than assuming the Provider API usage elsewhere makes it automatic.
 */
class ConfigurationCacheFunctionalTest {

    @TempDir
    Path dir;

    @ParameterizedTest
    @ValueSource(strings = {"8.5", "9.6.1"})
    void fatJar_storesAndReusesTheConfigurationCache_withNoProblems(String gradleVersion) throws Exception {
        Path jar = dir.resolve("libs/dep.jar");
        Files.createDirectories(jar.getParent());
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(jar.toFile()))) {
            putClass(zos, "com/example/dep/Foo");
        }

        Files.write(dir.resolve("settings.gradle"), "rootProject.name = 'app'\n".getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("build.gradle"), (
                "plugins {\n"
                + "  id 'java'\n"
                + "  id 'com.ljarocki.shaded-jar'\n"
                + "}\n"
                + "version = '1.0'\n"
                + "dependencies {\n"
                + "  implementation files('libs/dep.jar')\n"
                + "}\n"
                + "shadedJar {\n"
                + "  mainClass = 'com.example.Main'\n"
                + "  archiveClassifier = 'all'\n"
                + "  relocate 'com.example.dep', 'shaded.dep'\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));

        BuildResult first = runWithConfigCache(gradleVersion);
        assertEquals(TaskOutcome.SUCCESS, first.task(":fatJar").getOutcome());
        assertTrue(first.getOutput().contains("Configuration cache entry stored"), first.getOutput());
        assertFalse(first.getOutput().toLowerCase().contains("problems were found storing the configuration cache"),
                first.getOutput());
        assertJarIsCorrect();

        // Change nothing; a second run must reuse the cached configuration
        // (not just "also succeed") to actually prove serialization round-trips.
        BuildResult second = runWithConfigCache(gradleVersion);
        assertTrue(second.getOutput().contains("Reusing configuration cache")
                        || second.getOutput().contains("Configuration cache entry reused"),
                second.getOutput());
        assertJarIsCorrect();
    }

    private BuildResult runWithConfigCache(String gradleVersion) {
        return GradleRunner.create()
                .withGradleVersion(gradleVersion)
                .withProjectDir(dir.toFile())
                .withPluginClasspath()
                .withArguments("fatJar", "--configuration-cache", "--stacktrace")
                .build();
    }

    private void assertJarIsCorrect() throws Exception {
        Set<String> names = entryNames(dir.resolve("build/libs/app-1.0-all.jar"));
        assertTrue(names.contains("shaded/dep/Foo.class"), names.toString());
        assertFalse(names.contains("com/example/dep/Foo.class"), names.toString());
    }

    private static void putClass(ZipOutputStream zos, String internalName) throws Exception {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        cw.visitEnd();
        zos.putNextEntry(new ZipEntry(internalName + ".class"));
        zos.write(cw.toByteArray());
        zos.closeEntry();
    }

    private static Set<String> entryNames(Path jar) throws Exception {
        Set<String> names = new HashSet<>();
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> e = zf.entries();
            while (e.hasMoreElements()) names.add(e.nextElement().getName());
        }
        return names;
    }
}
