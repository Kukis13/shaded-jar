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
 * End-to-end proof that {@code minimize()} drops an unreferenced dependency
 * class through the real {@code fatJar} task — not just at the {@link
 * MinimizerTest} unit level — using a hand-built dependency jar (no network)
 * and a real project source file that references one of its two classes.
 */
class MinimizeFunctionalTest {

    @TempDir
    Path dir;

    private void writeDependencyJar() throws Exception {
        Path jar = dir.resolve("libs/dep.jar");
        Files.createDirectories(jar.getParent());
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(jar.toFile()))) {
            putClass(zos, "com/example/dep/Used");
            putClass(zos, "com/example/dep/Unused");
        }
    }

    private void writeAppSource() throws Exception {
        Path src = dir.resolve("src/main/java/com/example/App.java");
        Files.createDirectories(src.getParent());
        Files.write(src, (
                "package com.example;\n"
                + "public class App {\n"
                + "    public com.example.dep.Used field;\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void minimize_dropsAnUnreferencedDependencyClass_keepsAReferencedOne() throws Exception {
        writeDependencyJar();
        writeAppSource();

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
                + "  minimize()\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));

        BuildResult result = GradleRunner.create()
                .withProjectDir(dir.toFile())
                .withPluginClasspath()
                .withArguments("fatJar", "--stacktrace")
                .build();
        assertEquals(TaskOutcome.SUCCESS, result.task(":fatJar").getOutcome());

        Set<String> names = entryNames(dir.resolve("build/libs/app-all.jar"));

        assertTrue(names.contains("com/example/dep/Used.class"), "referenced class is kept: " + names);
        assertFalse(names.contains("com/example/dep/Unused.class"), "unreferenced class is dropped: " + names);
        assertTrue(names.contains("com/example/App.class"), "the project's own class is always kept: " + names);
    }

    @Test
    void minimizeKeep_exemptsAnOtherwiseDroppedClass() throws Exception {
        writeDependencyJar();
        writeAppSource();

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
                + "  minimize {\n"
                + "    keep 'com.example.dep.Unused'\n"
                + "  }\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));

        BuildResult result = GradleRunner.create()
                .withProjectDir(dir.toFile())
                .withPluginClasspath()
                .withArguments("fatJar", "--stacktrace")
                .build();
        assertEquals(TaskOutcome.SUCCESS, result.task(":fatJar").getOutcome());

        Set<String> names = entryNames(dir.resolve("build/libs/app-all.jar"));
        assertTrue(names.contains("com/example/dep/Unused.class"),
                "explicitly kept despite being otherwise unreferenced: " + names);
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
