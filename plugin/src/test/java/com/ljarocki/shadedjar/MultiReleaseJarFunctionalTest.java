package com.ljarocki.shadedjar;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end proof of multi-release JAR (JEP 238) awareness through the real
 * {@code fatJar} task, using a hand-built dependency jar (no network) that
 * mimics one: a base class plus a {@code META-INF/versions/17/} override of
 * the same class.
 *
 * <p>Two things had to work together for this to be more than cosmetic:
 * {@code Multi-Release: true} actually landing in the *output* jar's manifest
 * (without it the JVM never even looks at {@code META-INF/versions/}), and
 * the versioned override relocating the same way its base counterpart does
 * (previously it wouldn't relocate at all, since its entry name doesn't start
 * with the relocated package prefix — it starts with {@code META-INF/versions/N/}).
 */
class MultiReleaseJarFunctionalTest {

    @TempDir
    Path dir;

    private void writeMrjarDependency(Path jar) throws Exception {
        Files.createDirectories(jar.getParent());
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(jar.toFile()))) {
            zos.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            zos.write(("Manifest-Version: 1.0\nMulti-Release: true\n").getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            putClass(zos, "com/example/dep/Foo");
            putClass(zos, "META-INF/versions/17/com/example/dep/Foo");
        }
    }

    @Test
    void multiReleaseAttributeAndVersionedOverride_bothSurviveAPlainFatJar() throws Exception {
        writeMrjarDependency(dir.resolve("libs/dep.jar"));

        BuildResult result = run(
                "shadedJar {\n"
                + "  archiveClassifier = 'all'\n"
                + "}\n");
        assertEquals(TaskOutcome.SUCCESS, result.task(":fatJar").getOutcome());

        Path jar = dir.resolve("build/libs/app-all.jar");
        assertEquals("true", mainAttribute(jar, "Multi-Release"),
                "output manifest must declare Multi-Release, or the JVM ignores META-INF/versions/ entirely");

        Set<String> names = entryNames(jar);
        assertTrue(names.contains("com/example/dep/Foo.class"));
        assertTrue(names.contains("META-INF/versions/17/com/example/dep/Foo.class"),
                "versioned override kept as its own entry, not flattened/dropped");
    }

    @Test
    void relocation_appliesToTheVersionedOverrideTooNotJustTheBaseClass() throws Exception {
        writeMrjarDependency(dir.resolve("libs/dep.jar"));

        BuildResult result = run(
                "shadedJar {\n"
                + "  archiveClassifier = 'all'\n"
                + "  relocate 'com.example.dep', 'shaded.dep'\n"
                + "}\n");
        assertEquals(TaskOutcome.SUCCESS, result.task(":fatJar").getOutcome());

        Path jar = dir.resolve("build/libs/app-all.jar");
        assertEquals("true", mainAttribute(jar, "Multi-Release"));

        Set<String> names = entryNames(jar);
        assertTrue(names.contains("shaded/dep/Foo.class"), "base class relocated: " + names);
        assertTrue(names.contains("META-INF/versions/17/shaded/dep/Foo.class"),
                "versioned override relocated the same way, prefix preserved: " + names);
        assertFalse(names.contains("com/example/dep/Foo.class"), names.toString());
        assertFalse(names.contains("META-INF/versions/17/com/example/dep/Foo.class"),
                "versioned override must not be left pointing at the old package: " + names);
    }

    // --- helpers --------------------------------------------------------------

    private BuildResult run(String shadedJarBlock) throws Exception {
        Files.write(dir.resolve("settings.gradle"), "rootProject.name = 'app'\n".getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("build.gradle"), (
                "plugins {\n"
                + "  id 'java'\n"
                + "  id 'com.ljarocki.shaded-jar'\n"
                + "}\n"
                + "dependencies {\n"
                + "  implementation files('libs/dep.jar')\n"
                + "}\n"
                + shadedJarBlock).getBytes(StandardCharsets.UTF_8));
        return GradleRunner.create()
                .withProjectDir(dir.toFile())
                .withPluginClasspath()
                .withArguments("fatJar", "--stacktrace")
                .build();
    }

    private static void putClass(ZipOutputStream zos, String internalName) throws Exception {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        cw.visitEnd();
        zos.putNextEntry(new ZipEntry(internalName + ".class"));
        zos.write(cw.toByteArray());
        zos.closeEntry();
    }

    private static String mainAttribute(Path jar, String name) throws Exception {
        try (JarFile jf = new JarFile(jar.toFile())) {
            Manifest mf = jf.getManifest();
            return mf.getMainAttributes().getValue(name);
        }
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
