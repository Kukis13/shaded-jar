package com.ljarocki.shadedjar;

/**
 * Configures the optional scoping block on a {@code relocate(from, to) { ... }}
 * rule (see {@link ShadedJarExtension}). Without this block, a rule applies to
 * everything under its {@code from} prefix, as before.
 *
 * <p>Patterns are a small, deliberately non-full-glob subset — see {@code
 * Relocator.matchesPattern}: either an exact dotted class name ({@code
 * "com.example.Foo"}) or a prefix wildcard ({@code "com.example.**"}, matching
 * that package and everything nested under it).
 *
 * <pre>
 *   relocate 'com.google.common', 'com.example.shaded.guava', {
 *       // Leave this one annotation under its original name so a tool that
 *       // scans for it by exact FQCN still finds it.
 *       exclude 'com.google.common.annotations.VisibleForTesting'
 *   }
 * </pre>
 *
 * Excludes always win over includes. If any {@code include} patterns are given,
 * only names matching at least one of them are eligible at all; excludes are
 * then still applied on top.
 */
public interface RelocationSpec {
    void include(String... patterns);

    void exclude(String... patterns);
}
