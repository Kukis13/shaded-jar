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
 * Proves the plugin actually works on the two Gradle major versions the
 * README claims support for, rather than only ever running under whatever
 * version the wrapper happens to pin ({@code GradleRunner} otherwise defaults
 * to that). {@link GradleRunner#withGradleVersion} downloads and runs the
 * build against a specific, independent Gradle distribution.
 *
 * <p>{@code 8.5} is the practical floor here, not {@code 8.0}: it's the
 * earliest Gradle 8.x release that supports running on a JDK 21 daemon, which
 * is what this CI/dev environment uses — testing an older 8.x would need a
 * second, older JDK in the matrix. The plugin's own bytecode targets Java 8
 * and only uses Provider/Worker APIs stable since well before Gradle 8, so
 * there's no reason to expect anything between true 8.0 and 8.5 to behave
 * differently; this just isn't exercised directly.
 *
 * <p>Needs network the first time each Gradle version runs here (TestKit
 * downloads and caches the distribution), on top of the usual local
 * dependency jar built in-process (no Maven Central resolution needed for
 * the actual build under test).
 */
class GradleVersionCompatibilityFunctionalTest {

    @TempDir
    Path dir;

    @ParameterizedTest
    @ValueSource(strings = {"8.5", "9.6.1"})
    void fatAndShadedJarBuild_succeedsOnThisGradleVersion(String gradleVersion) throws Exception {
        Path jar = dir.resolve("libs/dep.jar");
        Files.createDirectories(jar.getParent());
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(jar.toFile()))) {
            putClass(zos, "com/example/dep/Foo");
            zos.putNextEntry(new ZipEntry("META-INF/services/com.example.Spi"));
            zos.write("com.example.dep.Impl\n".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        Files.write(dir.resolve("settings.gradle"), "rootProject.name = 'app'\n".getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("build.gradle"), (
                "plugins {\n"
                + "  id 'java'\n"
                + "  id 'com.ljarocki.shaded-jar'\n"
                + "}\n"
                + "dependencies {\n"
                + "  implementation files('libs/dep.jar')\n"
                + "}\n"
                + "shadedJar {\n"
                + "  archiveClassifier = 'all'\n"
                + "  relocate 'com.example.dep', 'shaded.dep'\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));

        BuildResult result = GradleRunner.create()
                .withGradleVersion(gradleVersion)
                .withProjectDir(dir.toFile())
                .withPluginClasspath()
                .withArguments("fatJar", "--stacktrace")
                .build();
        assertEquals(TaskOutcome.SUCCESS, result.task(":fatJar").getOutcome());

        Set<String> names = entryNames(dir.resolve("build/libs/app-all.jar"));
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
