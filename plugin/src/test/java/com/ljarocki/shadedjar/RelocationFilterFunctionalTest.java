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
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end proof that the {@code relocate(from, to) { include(...); exclude(...) }}
 * DSL scopes a relocation through the real {@code fatJar} task — not just at the
 * {@link RelocatorTest} unit level — using a hand-built dependency jar (no
 * network) with a couple of real (ASM-generated) class files.
 */
class RelocationFilterFunctionalTest {

    @TempDir
    Path dir;

    @Test
    void excludedSubpackage_isCarvedOutOfAnOtherwiseRelocatedPackage() throws Exception {
        Path jar = dir.resolve("libs/dep.jar");
        Files.createDirectories(jar.getParent());
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(jar.toFile()))) {
            putClass(zos, "com/example/dep/Foo");
            putClass(zos, "com/example/dep/keep/Kept");
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
                + "  relocate 'com.example.dep', 'shaded.dep', {\n"
                + "    exclude 'com.example.dep.keep.**'\n"
                + "  }\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));

        BuildResult result = GradleRunner.create()
                .withProjectDir(dir.toFile())
                .withPluginClasspath()
                .withArguments("fatJar", "--stacktrace")
                .build();
        assertEquals(TaskOutcome.SUCCESS, result.task(":fatJar").getOutcome());

        Set<String> names = entryNames(dir.resolve("build/libs/app-all.jar"));

        assertTrue(names.contains("shaded/dep/Foo.class"), "unexcluded class relocated: " + names);
        assertFalse(names.contains("com/example/dep/Foo.class"), "old path gone: " + names);

        assertTrue(names.contains("com/example/dep/keep/Kept.class"),
                "excluded subpackage keeps its original path: " + names);
        assertFalse(names.contains("shaded/dep/keep/Kept.class"),
                "excluded subpackage must not also appear at the relocated path: " + names);
    }

    // --- helpers --------------------------------------------------------------

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
