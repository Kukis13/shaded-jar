package com.ljarocki.shadedjar;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Package relocation: rewrites bundled classes and resources from a source
 * package prefix into a shaded one, so a fat JAR can carry a dependency without
 * its packages colliding with a different version on the consumer's classpath.
 *
 * <p>Each rule maps a dotted package prefix to another (e.g.
 * {@code com.google.common -> com.example.shaded.guava}). Relocation touches:
 * <ul>
 *   <li><b>class bytecode</b> — every type reference, descriptor and signature,
 *       via ASM's {@link ClassRemapper};
 *   <li><b>string constants</b> — {@code Class.forName("com.google.common.X")}
 *       style literals (best-effort, prefix-matched);
 *   <li><b>entry paths</b> — {@code com/google/common/Foo.class} and resources
 *       under a relocated package;
 *   <li><b>service files</b> — the {@code META-INF/services/<interface>} name and
 *       the provider class names inside.
 * </ul>
 *
 * Rules are applied longest-source-prefix first so nested relocations are
 * unambiguous. An empty relocator is a no-op (plain fat JAR).
 */
final class Relocator {

    private static final String SERVICES = "META-INF/services/";

    private static final class Rule {
        final String fromSlash, toSlash, fromDot, toDot;
        Rule(String from, String to) {
            this.fromDot = from;
            this.toDot = to;
            this.fromSlash = from.replace('.', '/');
            this.toSlash = to.replace('.', '/');
        }
    }

    private final List<Rule> rules;
    private final Remapper remapper;

    Relocator(Map<String, String> relocations) {
        List<Rule> r = new ArrayList<>();
        if (relocations != null) {
            for (Map.Entry<String, String> e : relocations.entrySet()) {
                if (e.getKey() != null && !e.getKey().isEmpty() && e.getValue() != null) {
                    r.add(new Rule(e.getKey(), e.getValue()));
                }
            }
        }
        // Longest source prefix first: a rule for a.b.c must win over one for a.b.
        r.sort((a, b) -> Integer.compare(b.fromSlash.length(), a.fromSlash.length()));
        this.rules = r;
        this.remapper = new Remapper() {
            @Override public String map(String internalName) {
                return mapSlash(internalName);
            }
            @Override public Object mapValue(Object value) {
                if (value instanceof String) {
                    String mapped = mapConstant((String) value);
                    if (mapped != null) return mapped;
                }
                return super.mapValue(value);
            }
        };
    }

    boolean isEmpty() {
        return rules.isEmpty();
    }

    // --- name mapping ---------------------------------------------------------

    /** Map a '/'-separated internal name or resource path by package prefix. */
    private String mapSlash(String name) {
        for (Rule rule : rules) {
            if (name.equals(rule.fromSlash)) return rule.toSlash;
            if (name.startsWith(rule.fromSlash + "/")) {
                return rule.toSlash + name.substring(rule.fromSlash.length());
            }
        }
        return name;
    }

    /** Map a '.'-separated (dotted) class name by package prefix. */
    private String mapDot(String name) {
        for (Rule rule : rules) {
            if (name.equals(rule.fromDot)) return rule.toDot;
            if (name.startsWith(rule.fromDot + ".")) {
                return rule.toDot + name.substring(rule.fromDot.length());
            }
        }
        return name;
    }

    /** Rewrite a string constant if it looks like a relocated name; else null. */
    private String mapConstant(String s) {
        String dot = mapDot(s);
        if (!dot.equals(s)) return dot;
        String slash = mapSlash(s);
        if (!slash.equals(s)) return slash;
        return null;
    }

    // --- public operations ----------------------------------------------------

    /** Rewrite class bytecode. */
    byte[] relocateClass(byte[] input) {
        ClassReader reader = new ClassReader(input);
        ClassWriter writer = new ClassWriter(0); // pure rename: no frame recompute needed
        reader.accept(new ClassRemapper(writer, remapper), 0);
        return writer.toByteArray();
    }

    /** Map an entry path: a {@code .class} file or a resource under a package. */
    String relocateEntryName(String name) {
        if (name.endsWith(".class")) {
            return mapSlash(name.substring(0, name.length() - 6)) + ".class";
        }
        return mapSlash(name);
    }

    /** Map a {@code META-INF/services/<interface>} file name (relocating the SPI). */
    String relocateServiceFileName(String name) {
        if (!name.startsWith(SERVICES)) return name;
        return SERVICES + mapDot(name.substring(SERVICES.length()));
    }

    /** Relocate the provider class names inside a service file's content. */
    String relocateServiceContent(String content) {
        StringBuilder sb = new StringBuilder(content.length());
        for (String line : content.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                sb.append(line).append('\n');
            } else {
                sb.append(mapDot(trimmed)).append('\n');
            }
        }
        return sb.toString();
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
