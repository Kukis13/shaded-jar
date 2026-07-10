package com.ljarocki.shadedjar;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Hermetic tests for {@link Relocator} — no Gradle, no network. */
class RelocatorTest {

    private static Relocator reloc(String... fromTo) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < fromTo.length; i += 2) m.put(fromTo[i], fromTo[i + 1]);
        return new Relocator(m);
    }

    @Test
    void emptyRelocatorIsNoOp() {
        Relocator r = new Relocator(Collections.emptyMap());
        assertTrue(r.isEmpty());
        assertEquals("com/google/common/Foo.class", r.relocateEntryName("com/google/common/Foo.class"));
    }

    @Test
    void relocatesClassAndResourcePaths() {
        Relocator r = reloc("com.google.common", "shaded.guava");
        assertEquals("shaded/guava/collect/ImmutableList.class",
                r.relocateEntryName("com/google/common/collect/ImmutableList.class"));
        assertEquals("shaded/guava/data.properties",
                r.relocateEntryName("com/google/common/data.properties"));
        // Unrelated packages are left alone.
        assertEquals("org/other/X.class", r.relocateEntryName("org/other/X.class"));
    }

    @Test
    void relocatesServiceFileNameOnlyWhenInterfaceMatches() {
        Relocator r = reloc("com.google.common", "shaded.guava");
        assertEquals("META-INF/services/shaded.guava.Spi",
                r.relocateServiceFileName("META-INF/services/com.google.common.Spi"));
        // A JDK SPI (not relocated) keeps its name.
        assertEquals("META-INF/services/java.sql.Driver",
                r.relocateServiceFileName("META-INF/services/java.sql.Driver"));
    }

    @Test
    void relocatesServiceContentKeepingComments() {
        Relocator r = reloc("com.google.common", "shaded.guava");
        String in = "# a comment\ncom.google.common.Impl\norg.other.Keep\n";
        String out = r.relocateServiceContent(in);
        assertTrue(out.contains("# a comment"), "comment preserved");
        assertTrue(out.contains("shaded.guava.Impl"), "provider relocated");
        assertTrue(out.contains("org.other.Keep"), "unrelated provider kept");
        assertFalse(out.contains("com.google.common.Impl"), "old provider gone");
    }

    @Test
    void longestSourcePrefixWins() {
        Relocator r = reloc("com.google", "a", "com.google.common", "b");
        assertEquals("b/X.class", r.relocateEntryName("com/google/common/X.class"));
        assertEquals("a/other/Y.class", r.relocateEntryName("com/google/other/Y.class"));
    }

    @Test
    void relocatesBytecodeTypesAndStringConstants() {
        // Generate a.b.Gen with a field of type a.b.Other and an LDC "a.b.Other".
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "a/b/Gen", null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC, "helper", "La/b/Other;", null, null).visitEnd();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "name", "()Ljava/lang/String;", null, null);
        mv.visitCode();
        mv.visitLdcInsn("a.b.Other");
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(1, 0);
        mv.visitEnd();
        cw.visitEnd();

        byte[] out = reloc("a.b", "c.d").relocateClass(cw.toByteArray());

        ClassReader cr = new ClassReader(out);
        assertEquals("c/d/Gen", cr.getClassName(), "class renamed");

        final String[] fieldDesc = {null};
        final Object[] ldc = {null};
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public FieldVisitor visitField(int a, String n, String d, String s, Object v) {
                fieldDesc[0] = d;
                return null;
            }
            @Override
            public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitLdcInsn(Object cst) {
                        ldc[0] = cst;
                    }
                };
            }
        }, 0);

        assertEquals("Lc/d/Other;", fieldDesc[0], "field type descriptor remapped");
        assertEquals("c.d.Other", ldc[0], "string constant remapped");
    }

    @Test
    void classBytesAreUnchangedWithNoMatchingRule() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "keep/me/Gen", null, "java/lang/Object", null);
        cw.visitEnd();
        byte[] in = cw.toByteArray();
        byte[] out = reloc("com.google.common", "shaded.guava").relocateClass(in);
        assertEquals("keep/me/Gen", new ClassReader(out).getClassName());
    }
}
