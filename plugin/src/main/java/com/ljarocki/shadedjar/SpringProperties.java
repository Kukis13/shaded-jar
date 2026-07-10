package com.ljarocki.shadedjar;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Handling for the three well-known Spring Framework/Boot classpath-scanned
 * resource files that a plain line-merge (like {@code META-INF/services/*})
 * would get wrong, because they're {@code key=value} Java {@link Properties}
 * files, not one-provider-per-line lists:
 *
 * <ul>
 *   <li>{@code META-INF/spring.factories} — key and value are both dotted class
 *       names; the value is a genuine comma-separated <em>list</em> (Spring's own
 *       {@code SpringFactoriesLoader} merges same-key entries across every copy
 *       of this file on the classpath the same way: union the comma-separated
 *       values). Both key and value are relocated.
 *   <li>{@code META-INF/spring.handlers} — key is an XML namespace URI (never
 *       relocated), value is a single dotted handler class name (relocated).
 *       Real duplicate keys are a genuine authoring conflict, not a list to
 *       merge, so we resolve them first-wins in classpath order and report
 *       the conflict (see {@link #accumulate}) rather than silently dropping it.
 *   <li>{@code META-INF/spring.schemas} — key and value are both non-class
 *       identifiers (a public/system ID and a classpath resource path), so
 *       neither is relocated; merged first-wins per key like {@code spring.handlers}.
 * </ul>
 *
 * <p>Deliberately avoids {@link Properties#store}: it always emits a
 * {@code #<timestamp>} comment line and iterates in {@code Hashtable} order,
 * both of which would break this project's byte-reproducible output. Output is
 * instead built by hand with explicit, sorted key order (see {@link #render}).
 */
final class SpringProperties {
    private SpringProperties() {}

    enum MergeMode { LIST_APPEND, FIRST_WINS }

    enum Kind {
        FACTORIES("META-INF/spring.factories", MergeMode.LIST_APPEND),
        HANDLERS("META-INF/spring.handlers", MergeMode.FIRST_WINS),
        SCHEMAS("META-INF/spring.schemas", MergeMode.FIRST_WINS);

        final String entryName;
        final MergeMode mergeMode;

        Kind(String entryName, MergeMode mergeMode) {
            this.entryName = entryName;
            this.mergeMode = mergeMode;
        }

        static Kind of(String entryName) {
            for (Kind k : values()) {
                if (k.entryName.equals(entryName)) return k;
            }
            return null;
        }
    }

    static Properties parse(byte[] content) throws IOException {
        Properties props = new Properties();
        try (InputStream in = new ByteArrayInputStream(content)) {
            props.load(in);
        }
        return props;
    }

    /**
     * Relocate one source's content for the given resource kind. A no-op for
     * {@link Kind#SCHEMAS} (nothing in it is a class name) and for an empty
     * relocator (plain fat jar).
     */
    static byte[] relocateContent(Kind kind, byte[] raw, Relocator relocator) throws IOException {
        if (kind == Kind.SCHEMAS || relocator.isEmpty()) return raw;
        Properties props = parse(raw);
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : props.stringPropertyNames()) {
            String value = props.getProperty(key);
            if (kind == Kind.FACTORIES) {
                out.put(relocator.relocateDottedName(key), relocateCommaList(value, relocator));
            } else {
                out.put(key, relocator.relocateDottedName(value));
            }
        }
        return render(out);
    }

    /**
     * Accumulate one source's entries into the running per-key merge for this
     * file. Returns a human-readable message for each {@link MergeMode#FIRST_WINS}
     * key that this call found already present with a genuinely different value
     * — a real cross-jar conflict, since that merge mode has no way to combine
     * two different values correctly. The caller (which has a build-tool logger,
     * unlike this pure/no-I/O class) is expected to surface these; the kept
     * (first) value is what ends up in the merged output either way.
     */
    static List<String> accumulate(Map<String, LinkedHashSet<String>> keyValues, Kind kind, byte[] body)
            throws IOException {
        List<String> conflicts = new ArrayList<>();
        Properties props = parse(body);
        List<String> keys = new ArrayList<>();
        for (Object k : props.keySet()) keys.add((String) k);
        Collections.sort(keys); // deterministic regardless of Properties' Hashtable iteration order

        for (String key : keys) {
            String value = props.getProperty(key);
            LinkedHashSet<String> values = keyValues.computeIfAbsent(key, k -> new LinkedHashSet<>());
            if (kind.mergeMode == MergeMode.FIRST_WINS) {
                if (values.isEmpty()) {
                    values.add(value);
                } else if (!values.contains(value)) {
                    conflicts.add(kind.entryName + ": key '" + key + "' has conflicting values — keeping '"
                            + values.iterator().next() + "', discarding '" + value + "'");
                }
            } else {
                for (String part : value.split(",")) {
                    String trimmed = part.trim();
                    if (!trimmed.isEmpty()) values.add(trimmed);
                }
            }
        }
        return conflicts;
    }

    /** Render the fully-merged per-key values (joined with {@code ,} for LIST_APPEND keys). */
    static byte[] renderMerged(Map<String, LinkedHashSet<String>> keyValues) throws IOException {
        Map<String, String> flat = new LinkedHashMap<>();
        for (Map.Entry<String, LinkedHashSet<String>> e : keyValues.entrySet()) {
            flat.put(e.getKey(), String.join(",", e.getValue()));
        }
        return render(flat);
    }

    private static String relocateCommaList(String value, Relocator relocator) {
        String[] parts = value.split(",");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(relocator.relocateDottedName(parts[i].trim()));
        }
        return sb.toString();
    }

    /** Sorted, byte-reproducible {@code key=value} serialization — see the class doc for why not {@link Properties#store}. */
    private static byte[] render(Map<String, String> keyValues) throws IOException {
        List<String> keys = new ArrayList<>(keyValues.keySet());
        Collections.sort(keys);
        StringBuilder sb = new StringBuilder();
        for (String key : keys) {
            sb.append(escapedLine(key, keyValues.get(key))).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * One correctly-escaped {@code key=value} line, obtained by round-tripping a
     * single-entry {@link Properties#store} and discarding its timestamp comment.
     * Reuses the JDK's own (fiddly) escaping rules instead of reimplementing them,
     * and — because it's always exactly one entry — sidesteps both problems with
     * using {@code store()} directly on the full map (timestamp line, Hashtable
     * iteration order).
     */
    private static String escapedLine(String key, String value) throws IOException {
        Properties single = new Properties();
        single.setProperty(key, value);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        single.store(bos, null);
        for (String line : bos.toString("8859_1").split("\r?\n")) {
            if (!line.isEmpty() && line.charAt(0) != '#') return line;
        }
        throw new IOException("failed to serialize Spring properties entry: " + key);
    }
}
