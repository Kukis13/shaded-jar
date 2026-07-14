package com.ljarocki.shadedjar;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Set;

import static java.util.Arrays.asList;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hermetic tests for {@link Minimizer} — a small synthetic classpath, no
 * Gradle, no network. Uses real {@code .class} files on disk (directories,
 * not jars — jdependency's {@code Clazzpath.addClazzpathUnit(File)} handles
 * both, and a directory is simpler to construct in a test).
 */
class MinimizerTest {

    /** Writes a minimal class {@code internalName} with one field of type {@code fieldTypeInternalName} (or none). */
    private static void writeClass(File root, String internalName, String fieldTypeInternalName) throws IOException {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        if (fieldTypeInternalName != null) {
            cw.visitField(Opcodes.ACC_PUBLIC, "ref", "L" + fieldTypeInternalName + ";", null, null).visitEnd();
        }
        cw.visitEnd();
        File classFile = new File(root, internalName + ".class");
        classFile.getParentFile().mkdirs();
        Files.write(classFile.toPath(), cw.toByteArray());
    }

    @Test
    void unreferencedDependencyClass_isDropped_referencedOneIsKept(@TempDir File tmp) throws IOException {
        File project = new File(tmp, "project");
        File deps = new File(tmp, "deps");

        // Project's own class references a/A (field type) but never a/B.
        writeClass(project, "p/Main", "a/A");
        writeClass(deps, "a/A", null);
        writeClass(deps, "a/B", null);

        Set<String> drop = Minimizer.computeDropClasses(
                asList(project), asList(deps), Collections.emptySet());

        assertTrue(drop.contains("a/B"), "unreferenced dependency class is dropped");
        assertFalse(drop.contains("a/A"), "referenced dependency class is kept");
        assertFalse(drop.contains("p/Main"), "the project's own class is never eligible for dropping");
    }

    @Test
    void keepPattern_exemptsAnOtherwiseRemovableClass(@TempDir File tmp) throws IOException {
        File project = new File(tmp, "project");
        File deps = new File(tmp, "deps");

        writeClass(project, "p/Main", null); // references nothing
        writeClass(deps, "a/B", null);       // unreferenced -> removable unless kept

        Set<String> dropWithoutKeep = Minimizer.computeDropClasses(
                asList(project), asList(deps), Collections.emptySet());
        assertTrue(dropWithoutKeep.contains("a/B"));

        Set<String> dropWithKeep = Minimizer.computeDropClasses(
                asList(project), asList(deps), Collections.singleton("a.B"));
        assertFalse(dropWithKeep.contains("a/B"), "explicit keep exempts it despite being unreferenced");
    }

    @Test
    void keepPattern_prefixWildcardExemptsWholeSubpackage(@TempDir File tmp) throws IOException {
        File project = new File(tmp, "project");
        File deps = new File(tmp, "deps");

        writeClass(project, "p/Main", null);
        writeClass(deps, "a/plugin/One", null);
        writeClass(deps, "a/plugin/nested/Two", null);
        writeClass(deps, "a/other/Three", null);

        Set<String> drop = Minimizer.computeDropClasses(
                asList(project), asList(deps), Collections.singleton("a.plugin.**"));

        assertFalse(drop.contains("a/plugin/One"));
        assertFalse(drop.contains("a/plugin/nested/Two"));
        assertTrue(drop.contains("a/other/Three"), "outside the keep pattern, still dropped");
    }
}
