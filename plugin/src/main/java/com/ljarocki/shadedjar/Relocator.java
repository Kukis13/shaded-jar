package com.ljarocki.shadedjar;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * <p>A rule can optionally be scoped with include/exclude patterns (see the
 * {@code relocate(from, to) { include(...); exclude(...) }} DSL in {@link
 * ShadedJarExtension}), so e.g. a public API type or an annotation meant to be
 * found by its original name can be carved out of an otherwise-relocated
 * package. Patterns are deliberately a small subset of Shadow/Ant-style globs,
 * not the full syntax — see {@link NamePatterns}. A name that a rule's
 * prefix matches but whose filter rejects falls through to the next
 * (shorter-prefix) rule instead of being left unrelocated outright, so an
 * exclude can "un-relocate" a subpackage nested inside a broader rule.
 *
 * <p>Multi-release JAR version overrides ({@code META-INF/versions/N/...})
 * relocate consistently with their base counterpart — see {@link
 * #relocateEntryName}. The generated manifest's {@code Multi-Release: true}
 * attribute (needed for the JVM to even look at that directory) is handled
 * separately, in {@code FatJarTask}, since it depends on the merged set of
 * entries across every source rather than any one entry's own name.
 *
 * <p>Rules are applied longest-source-prefix first so nested relocations are
 * unambiguous. An empty relocator is a no-op (plain fat JAR).
 */
final class Relocator {

    private static final String SERVICES = "META-INF/services/";

    /**
     * Multi-release JAR (JEP 238) version-specific override directory, e.g.
     * {@code META-INF/versions/17/com/google/common/Foo.class}. Entry paths
     * under here relocate the same way their base (unversioned) counterpart
     * does — see {@link #relocateEntryName}.
     */
    private static final Pattern MRJAR_VERSION_PREFIX = Pattern.compile("^META-INF/versions/\\d+/");

    private static final class Rule {
        final String fromSlash, toSlash, fromDot, toDot;
        final List<String> includes, excludes;

        Rule(String from, String to, List<String> includes, List<String> excludes) {
            this.fromDot = from;
            this.toDot = to;
            this.fromSlash = from.replace('.', '/');
            this.toSlash = to.replace('.', '/');
            this.includes = includes;
            this.excludes = excludes;
        }

        /** Whether this rule (whose prefix already matches {@code dotName}) actually applies to it. */
        boolean isEligible(String dotName) {
            for (String pattern : excludes) {
                if (NamePatterns.matches(pattern, dotName)) return false;
            }
            if (includes.isEmpty()) return true;
            for (String pattern : includes) {
                if (NamePatterns.matches(pattern, dotName)) return true;
            }
            return false;
        }
    }

    private final List<Rule> rules;
    private final Remapper remapper;

    Relocator(Map<String, String> relocations) {
        this(relocations, Collections.emptyMap(), Collections.emptyMap());
    }

    /**
     * @param relocationIncludes optional, keyed by the same {@code from} prefix as
     *     {@code relocations}; value is a comma-separated pattern list (see
     *     {@link NamePatterns}). A prefix with no entry here means "no include
     *     filter" — everything under the prefix is eligible, the original behavior.
     * @param relocationExcludes same shape as {@code relocationIncludes}; excludes
     *     always win over includes.
     */
    Relocator(Map<String, String> relocations, Map<String, String> relocationIncludes,
              Map<String, String> relocationExcludes) {
        List<Rule> r = new ArrayList<>();
        if (relocations != null) {
            for (Map.Entry<String, String> e : relocations.entrySet()) {
                if (e.getKey() != null && !e.getKey().isEmpty() && e.getValue() != null) {
                    List<String> includes = splitCsv(get(relocationIncludes, e.getKey()));
                    List<String> excludes = splitCsv(get(relocationExcludes, e.getKey()));
                    r.add(new Rule(e.getKey(), e.getValue(), includes, excludes));
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

    private static String get(Map<String, String> map, String key) {
        return map == null ? null : map.get(key);
    }

    private static List<String> splitCsv(String s) {
        if (s == null || s.isEmpty()) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (String part : s.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out;
    }

    boolean isEmpty() {
        return rules.isEmpty();
    }

    // --- name mapping ---------------------------------------------------------

    /** Map a '/'-separated internal name or resource path by package prefix. */
    private String mapSlash(String name) {
        String dotName = name.replace('/', '.');
        for (Rule rule : rules) {
            boolean matchesPrefix = dotName.equals(rule.fromDot) || dotName.startsWith(rule.fromDot + ".");
            if (!matchesPrefix || !rule.isEligible(dotName)) continue;
            if (name.equals(rule.fromSlash)) return rule.toSlash;
            return rule.toSlash + name.substring(rule.fromSlash.length());
        }
        return name;
    }

    /** Map a '.'-separated (dotted) class name by package prefix. */
    private String mapDot(String name) {
        for (Rule rule : rules) {
            boolean matchesPrefix = name.equals(rule.fromDot) || name.startsWith(rule.fromDot + ".");
            if (!matchesPrefix || !rule.isEligible(name)) continue;
            if (name.equals(rule.fromDot)) return rule.toDot;
            return rule.toDot + name.substring(rule.fromDot.length());
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

    /**
     * Map an entry path: a {@code .class} file or a resource under a package.
     * A multi-release-JAR version override ({@code META-INF/versions/N/...})
     * is relocated the same way its base counterpart would be — the version
     * prefix is stripped, the rest is mapped normally, then the prefix is
     * reattached — so a versioned override doesn't end up pointing at the
     * dependency's original (unrelocated) package while the base class moves.
     */
    String relocateEntryName(String name) {
        Matcher m = MRJAR_VERSION_PREFIX.matcher(name);
        if (m.find()) {
            String prefix = m.group();
            return prefix + relocateEntryName(name.substring(prefix.length()));
        }
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

    /**
     * Relocate a single dotted class name by package prefix. Public entry point
     * onto {@link #mapDot} for callers outside the service-file/entry-name cases
     * above — currently the Spring {@code spring.factories}/{@code spring.handlers}
     * resource-file support in {@link SpringProperties}.
     */
    String relocateDottedName(String name) {
        return mapDot(name);
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
