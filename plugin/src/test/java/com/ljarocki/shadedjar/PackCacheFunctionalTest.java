package com.ljarocki.shadedjar;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves {@link PackCache} actually survives across separate builds the way CI
 * needs it to — not just within one {@code GradleRunner.build()} call, which
 * would only prove the plumbing compiles.
 *
 * <p>The scenario this simulates: a CI job checks out the repo fresh (new
 * project directory, nothing local) but restores a persisted {@code
 * ~/.gradle/caches} from a previous run (which most Gradle CI setups already do
 * for the dependency cache — {@code gradle/actions/setup-gradle} and {@code
 * actions/setup-java}'s {@code cache: gradle} option both do this by default).
 * That's modeled here with two *separate* project directories built via two
 * *separate* {@code GradleRunner} invocations, both pointed at the same {@code
 * --gradle-user-home} — the CLI flag that actually controls what {@code
 * Project.getGradle().getGradleUserHomeDir()} resolves to inside the build,
 * which is the deterministic lever to use here rather than relying on
 * TestKit's own (version-dependent, less-documented) default isolation
 * behavior for Gradle user home.
 *
 * <p>The Gradle user home directory is deliberately <em>not</em> a JUnit
 * {@code @TempDir} — TestKit drives builds through the Tooling API, which
 * always goes through a live daemon (it rejects {@code --no-daemon} outright),
 * and on Windows that daemon can still be holding file handles open under our
 * custom user home by the time the test method returns, which makes
 * {@code @TempDir}'s automatic recursive delete fail. It's created and
 * best-effort cleaned up manually instead.
 */
class PackCacheFunctionalTest {

    @TempDir
    Path root;

    private Path gradleUserHome;

    @AfterEach
    void cleanUpGradleUserHome() {
        if (gradleUserHome == null) return;
        try {
            deleteRecursivelyBestEffort(gradleUserHome.toFile());
        } catch (RuntimeException ignored) {
            // Same reasoning as the class doc: a lingering daemon may still hold a
            // handle open on Windows. Harmless leftover in the OS temp directory.
        }
    }

    @Test
    void packCache_isReusedAcrossSeparateProjectCheckouts_givenTheSamePersistedGradleUserHome() throws Exception {
        gradleUserHome = Files.createTempDirectory("shaded-jar-pack-cache-test-");

        // Same dependency bytes and same relocation config in both "checkouts" —
        // different project directories entirely, simulating two independent CI
        // runs sharing only a restored ~/.gradle/caches.
        byte[] depJarBytes = buildDepJar();

        BuildResult first = runInFreshProject(depJarBytes);
        assertEquals(TaskOutcome.SUCCESS, first.task(":fatJar").getOutcome());
        assertTrue(first.getOutput().contains("pack-cache: 0 hit"), first.getOutput());

        BuildResult second = runInFreshProject(depJarBytes);
        assertEquals(TaskOutcome.SUCCESS, second.task(":fatJar").getOutcome());
        assertFalse(second.getOutput().contains("pack-cache: 0 hit"),
                "the dependency jar should hit the cache restored from the shared Gradle user home: "
                        + second.getOutput());
    }

    @Test
    void packCache_missesAgain_ifTheRelocationConfigChanges_evenWithTheSameGradleUserHome() throws Exception {
        gradleUserHome = Files.createTempDirectory("shaded-jar-pack-cache-test-");
        byte[] depJarBytes = buildDepJar();

        BuildResult first = runInFreshProject(depJarBytes, "com.example.dep", "shaded.dep");
        assertTrue(first.getOutput().contains("pack-cache: 0 hit"), first.getOutput());

        // Different relocation target for the same dependency bytes: must not
        // reuse the previous entry, or the output would silently keep the old
        // (now-wrong) package name.
        BuildResult second = runInFreshProject(depJarBytes, "com.example.dep", "shaded.different");
        assertTrue(second.getOutput().contains("pack-cache: 0 hit"),
                "a relocation config change must miss the cache, not silently reuse a stale entry: "
                        + second.getOutput());
    }

    // --- helpers --------------------------------------------------------------

    private byte[] buildDepJar() throws Exception {
        Path tmpJar = Files.createTempFile(root, "dep", ".jar");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tmpJar.toFile()))) {
            ClassWriter cw = new ClassWriter(0);
            cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "com/example/dep/Foo", null, "java/lang/Object", null);
            cw.visitEnd();
            zos.putNextEntry(new ZipEntry("com/example/dep/Foo.class"));
            zos.write(cw.toByteArray());
            zos.closeEntry();
        }
        return Files.readAllBytes(tmpJar);
    }

    private BuildResult runInFreshProject(byte[] depJarBytes) throws Exception {
        return runInFreshProject(depJarBytes, "com.example.dep", "shaded.dep");
    }

    private BuildResult runInFreshProject(byte[] depJarBytes, String relocateFrom, String relocateTo) throws Exception {
        Path projectDir = Files.createTempDirectory(root, "project-");
        Path libs = projectDir.resolve("libs");
        Files.createDirectories(libs);
        Files.write(libs.resolve("dep.jar"), depJarBytes);

        Files.write(projectDir.resolve("settings.gradle"), "rootProject.name = 'app'\n".getBytes(StandardCharsets.UTF_8));
        Files.write(projectDir.resolve("build.gradle"), (
                "plugins {\n"
                + "  id 'java'\n"
                + "  id 'com.ljarocki.shaded-jar'\n"
                + "}\n"
                + "dependencies {\n"
                + "  implementation files('libs/dep.jar')\n"
                + "}\n"
                + "shadedJar {\n"
                + "  archiveClassifier = 'all'\n"
                + "  relocate '" + relocateFrom + "', '" + relocateTo + "'\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));

        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments("--gradle-user-home", gradleUserHome.toAbsolutePath().toString(),
                        "--stacktrace", "fatJar")
                .build();
    }

    private static void deleteRecursivelyBestEffort(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursivelyBestEffort(k);
        f.delete();
    }
}
