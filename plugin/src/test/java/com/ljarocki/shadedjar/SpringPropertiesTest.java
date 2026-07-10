package com.ljarocki.shadedjar;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Hermetic tests for {@link SpringProperties} — no Gradle, no I/O beyond
 * in-memory byte arrays.
 */
class SpringPropertiesTest {

    @Test
    void kindOf_recognizesTheThreeKnownFilesOnly() {
        assertEquals(SpringProperties.Kind.FACTORIES, SpringProperties.Kind.of("META-INF/spring.factories"));
        assertEquals(SpringProperties.Kind.HANDLERS, SpringProperties.Kind.of("META-INF/spring.handlers"));
        assertEquals(SpringProperties.Kind.SCHEMAS, SpringProperties.Kind.of("META-INF/spring.schemas"));
        assertNull(SpringProperties.Kind.of("META-INF/services/java.sql.Driver"));
        assertNull(SpringProperties.Kind.of("META-INF/spring.factories.bak"));
    }

    @Test
    void factories_mergeUnionsCommaSeparatedValuesPerKey_deduped() throws Exception {
        Map<String, LinkedHashSet<String>> merged = new LinkedHashMap<>();
        SpringProperties.accumulate(merged, SpringProperties.Kind.FACTORIES,
                utf8("org.example.Ext=com.a.One,com.a.Two\n"));
        SpringProperties.accumulate(merged, SpringProperties.Kind.FACTORIES,
                utf8("org.example.Ext=com.a.Two,com.a.Three\norg.example.Other=com.b.X\n"));

        byte[] rendered = SpringProperties.renderMerged(merged);
        Properties out = SpringProperties.parse(rendered);

        assertEquals(commaSet("com.a.One,com.a.Two,com.a.Three"), commaSet(out.getProperty("org.example.Ext")));
        assertEquals("com.b.X", out.getProperty("org.example.Other"));
    }

    @Test
    void handlersAndSchemas_mergeIsFirstWinsPerKey_notCommaAppended() throws Exception {
        // ':' is itself a Properties key/value separator, so a real spring.handlers
        // file escapes it in the URI key as '\:' — mirror that here.
        Map<String, LinkedHashSet<String>> merged = new LinkedHashMap<>();
        SpringProperties.accumulate(merged, SpringProperties.Kind.HANDLERS,
                utf8("http\\://a=com.a.HandlerA\n"));
        SpringProperties.accumulate(merged, SpringProperties.Kind.HANDLERS,
                utf8("http\\://a=com.b.HandlerB\nhttp\\://b=com.b.HandlerBB\n"));

        byte[] rendered = SpringProperties.renderMerged(merged);
        Properties out = SpringProperties.parse(rendered);

        assertEquals("com.a.HandlerA", out.getProperty("http://a"), "first source wins, not comma-joined");
        assertEquals("com.b.HandlerBB", out.getProperty("http://b"));
    }

    @Test
    void factories_relocationRewritesBothKeyAndEachListedValue() throws Exception {
        Relocator relocator = reloc("com.google.common", "shaded.guava");
        byte[] in = utf8("com.google.common.Ext=com.google.common.Impl,org.other.Keep\n");

        byte[] out = SpringProperties.relocateContent(SpringProperties.Kind.FACTORIES, in, relocator);
        Properties props = SpringProperties.parse(out);

        assertEquals(1, props.size());
        assertEquals("org.other.Keep,shaded.guava.Impl",
                commaSetAsSortedString(props.getProperty("shaded.guava.Ext")));
    }

    @Test
    void handlers_relocationRewritesValueOnly_keyUriIsNeverTouched() throws Exception {
        Relocator relocator = reloc("com.google.common", "shaded.guava");
        byte[] in = utf8("http\\://example.com/schema=com.google.common.HandlerX\n");

        byte[] out = SpringProperties.relocateContent(SpringProperties.Kind.HANDLERS, in, relocator);
        Properties props = SpringProperties.parse(out);

        assertEquals("shaded.guava.HandlerX", props.getProperty("http://example.com/schema"));
    }

    @Test
    void schemas_isNeverRelocated_evenWithMatchingRules() throws Exception {
        Relocator relocator = reloc("com.google.common", "shaded.guava");
        byte[] in = utf8("http://example.com/schema=com/google/common/schema.xsd\n");

        byte[] out = SpringProperties.relocateContent(SpringProperties.Kind.SCHEMAS, in, relocator);
        assertSame(in, out, "schemas content is untouched, not merely unchanged in value");
    }

    @Test
    void relocateContent_isNoOpForAnyKindWhenRelocatorIsEmpty() throws Exception {
        Relocator empty = reloc();
        byte[] in = utf8("com.google.common.Ext=com.google.common.Impl\n");
        for (SpringProperties.Kind kind : SpringProperties.Kind.values()) {
            byte[] out = SpringProperties.relocateContent(kind, in, empty);
            assertSame(in, out, kind + " must be untouched with no relocation rules");
        }
    }

    @Test
    void render_roundTripsSpecialCharactersAndEmitsNoTimestampComment() throws Exception {
        Map<String, LinkedHashSet<String>> merged = new LinkedHashMap<>();
        LinkedHashSet<String> value = new LinkedHashSet<>();
        value.add("C:\\Program Files\\App: v1");
        merged.put("some.Key", value);

        byte[] rendered = SpringProperties.renderMerged(merged);
        String text = new String(rendered, StandardCharsets.UTF_8);
        for (String line : text.split("\n")) {
            assertFalse(line.startsWith("#"), "no Properties#store timestamp comment: " + line);
        }

        Properties reparsed = SpringProperties.parse(rendered);
        assertEquals("C:\\Program Files\\App: v1", reparsed.getProperty("some.Key"));
    }

    // --- helpers ------------------------------------------------------------

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static Relocator reloc(String... fromTo) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < fromTo.length; i += 2) m.put(fromTo[i], fromTo[i + 1]);
        return new Relocator(m);
    }

    private static Set<String> commaSet(String value) {
        return new HashSet<>(Arrays.asList(value.split(",")));
    }

    private static String commaSetAsSortedString(String value) {
        String[] parts = value.split(",");
        Arrays.sort(parts);
        return String.join(",", parts);
    }
}
