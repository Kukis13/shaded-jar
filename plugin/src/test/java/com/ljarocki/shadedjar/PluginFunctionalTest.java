package com.ljarocki.shadedjar;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests that apply the plugin to a real generated project via Gradle
 * TestKit, build the jar, and assert on its structure and runtime behaviour.
 * These resolve a few real dependencies from Maven Central, so they need network.
 */
class PluginFunctionalTest {

    @TempDir
    Path dir;

    private static final String MAIN =
            "package com.example;\n"
            + "import com.google.common.collect.ImmutableList;\n"
            + "public class Main {\n"
            + "  public static void main(String[] args) {\n"
            + "    ImmutableList<Integer> l = ImmutableList.of(1, 2, 3);\n"
            + "    System.out.println(\"RESULT sum=\" + l.stream().mapToInt(i->i).sum()\n"
            + "        + \" impl=\" + ImmutableList.class.getName());\n"
            + "  }\n"
            + "}\n";

    private void writeProject(boolean relocate) throws Exception {
        write(dir.resolve("settings.gradle"), "rootProject.name = 'app'\n");
        String relocLine = relocate
                ? "  relocate 'com.google.common', 'com.example.shaded.guava'\n" : "";
        write(dir.resolve("build.gradle"),
                "plugins {\n"
                + "  id 'java'\n"
                + "  id 'com.ljarocki.shaded-jar'\n"
                + "}\n"
                + "group = 'com.example'\n"
                + "version = '1.0'\n"
                + "repositories { mavenCentral() }\n"
                + "dependencies {\n"
                + "  implementation 'com.google.guava:guava:33.0.0-jre'\n"
                + "  implementation 'com.h2database:h2:2.2.224'\n"
                + "  implementation 'org.postgresql:postgresql:42.7.1'\n"
                + "  implementation 'org.bouncycastle:bcprov-jdk18on:1.77'\n" // signed jar
                + "}\n"
                + "shadedJar {\n"
                + "  mainClass = 'com.example.Main'\n"
                + relocLine
                + "}\n");
        Path main = dir.resolve("src/main/java/com/example/Main.java");
        Files.createDirectories(main.getParent());
        write(main, MAIN);
    }

    private BuildResult run(String... args) {
        return GradleRunner.create()
                .withProjectDir(dir.toFile())
                .withPluginClasspath()
                .withArguments(args)
                .build();
    }

    @Test
    void shadedJar_relocates_mergesServices_stripsSignatures_andRuns() throws Exception {
        writeProject(true);
        BuildResult result = run("fatJar", "--stacktrace");
        assertEquals(TaskOutcome.SUCCESS, result.task(":fatJar").getOutcome());

        Path jar = dir.resolve("build/libs/app-1.0-all.jar");
        assertTrue(Files.exists(jar), "archive produced");
        Set<String> names = entryNames(jar);

        // Relocation: guava moved, nothing left in the original namespace.
        assertTrue(names.contains("com/example/shaded/guava/collect/ImmutableList.class"),
                "guava relocated");
        assertTrue(names.stream().noneMatch(n -> n.startsWith("com/google/common/")),
                "no classes left under com/google/common/");

        // Manifest carries the Main-Class.
        assertEquals("com.example.Main", mainClass(jar));

        // Service files merged: both JDBC drivers survive.
        String drivers = readEntry(jar, "META-INF/services/java.sql.Driver");
        assertTrue(drivers.contains("org.h2.Driver"), "h2 driver kept");
        assertTrue(drivers.contains("org.postgresql.Driver"), "postgres driver kept");

        // Signature files from the signed bouncycastle jar are stripped.
        assertTrue(names.stream().noneMatch(n ->
                        n.matches("META-INF/.*\\.(SF|DSA|RSA|EC)")),
                "signature files removed");

        // It actually runs, with guava resolved from the shaded namespace.
        String out = runMain(jar);
        assertTrue(out.contains("RESULT sum=6"), out);
        assertTrue(out.contains("impl=com.example.shaded.guava.collect.ImmutableList"), out);
    }

    @Test
    void fatJar_withoutRelocation_keepsOriginalPackages_andIsUpToDateOnRebuild() throws Exception {
        writeProject(false);
        BuildResult first = run("fatJar");
        assertEquals(TaskOutcome.SUCCESS, first.task(":fatJar").getOutcome());

        Path jar = dir.resolve("build/libs/app-1.0-all.jar");
        Set<String> names = entryNames(jar);
        assertTrue(names.contains("com/google/common/collect/ImmutableList.class"),
                "guava kept in original namespace");

        String out = runMain(jar);
        assertTrue(out.contains("impl=com.google.common.collect.ImmutableList"), out);

        // Incrementality: nothing changed -> task is UP-TO-DATE.
        BuildResult second = run("fatJar");
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":fatJar").getOutcome());
    }

    // --- helpers --------------------------------------------------------------

    private static void write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
    }

    private static Set<String> entryNames(Path jar) throws Exception {
        Set<String> names = new HashSet<>();
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            java.util.Enumeration<? extends ZipEntry> e = zf.entries();
            while (e.hasMoreElements()) names.add(e.nextElement().getName());
        }
        return names;
    }

    private static String readEntry(Path jar, String name) throws Exception {
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            ZipEntry e = zf.getEntry(name);
            assertTrue(e != null, "entry present: " + name);
            byte[] b = zf.getInputStream(e).readAllBytes();
            return new String(b, StandardCharsets.UTF_8);
        }
    }

    private static String mainClass(Path jar) throws Exception {
        try (JarFile jf = new JarFile(jar.toFile())) {
            Manifest mf = jf.getManifest();
            return mf.getMainAttributes().getValue("Main-Class");
        }
    }

    private static String runMain(Path jar) throws Exception {
        PrintStream orig = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf, true, "UTF-8"));
        try (URLClassLoader cl = new URLClassLoader(
                new URL[]{jar.toUri().toURL()}, ClassLoader.getSystemClassLoader())) {
            Class<?> main = Class.forName("com.example.Main", true, cl);
            main.getMethod("main", String[].class).invoke(null, (Object) new String[0]);
        } finally {
            System.setOut(orig);
        }
        return buf.toString("UTF-8");
    }
}
