package com.ljarocki.shadedjar;

/**
 * Configures the optional scoping block on {@code minimize { ... }} (see
 * {@link ShadedJarExtension}). Without this block, every class in every
 * bundled dependency that the reachability analysis can't prove is used gets
 * dropped.
 *
 * <p>{@code keep} takes the same small pattern language relocation's {@code
 * include}/{@code exclude} already use — see {@link NamePatterns}: either an
 * exact dotted class name ({@code "com.example.Foo"}) or a prefix wildcard
 * ({@code "com.example.**"}, matching that package and everything nested
 * under it). A class matching any {@code keep} pattern is never dropped,
 * regardless of what the reachability analysis concluded.
 *
 * <pre>
 *   minimize {
 *       // Only ever looked up by name via ServiceLoader/reflection, so the
 *       // static reachability analysis can't see it's actually used.
 *       keep 'com.example.plugin.ReflectivelyLoadedThing'
 *       keep 'org.some.plugin.**'
 *   }
 * </pre>
 */
public interface MinimizeSpec {
    void keep(String... patterns);
}
