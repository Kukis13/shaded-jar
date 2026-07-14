package com.ljarocki.shadedjar;

/**
 * Small, shared, deliberately non-full-glob pattern matcher used by both
 * relocation's {@code include}/{@code exclude} scoping ({@link Relocator})
 * and {@code minimize()}'s {@code keep} list ({@link Minimizer}).
 *
 * <p>Only two forms are recognized: {@code "a.b.C"} (no wildcard) matches
 * that exact dotted name; {@code "a.b.**"} matches {@code "a.b"} itself and
 * everything nested under it ({@code "a.b.C"}, {@code "a.b.c.D"}, ...) — the
 * same prefix semantics relocation rules themselves use. There is no
 * mid-segment {@code *} wildcard and no support for multiple {@code **} in
 * one pattern; those would need real glob-to-regex compilation for
 * comparatively little real-world benefit over these two forms.
 */
final class NamePatterns {

    private NamePatterns() {
    }

    static boolean matches(String pattern, String dotName) {
        if (pattern.equals("**")) return true;
        if (pattern.endsWith(".**")) {
            String prefix = pattern.substring(0, pattern.length() - 3);
            return dotName.equals(prefix) || dotName.startsWith(prefix + ".");
        }
        return dotName.equals(pattern);
    }
}
