package com.ljarocki.shadedjar;

import org.gradle.api.Action;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * DSL for the plugin:
 *
 * <pre>
 *   shadedJar {
 *       mainClass = 'com.example.Main'
 *       archiveClassifier = 'all'
 *       relocate 'com.google.common', 'com.example.shaded.guava'   // optional
 *       relocate 'org.apache.commons', 'com.example.shaded.commons', {
 *           exclude 'org.apache.commons.logging.**'   // scoped to a subset — optional
 *       }
 *   }
 * </pre>
 *
 * With no {@code relocate(...)} rules the task builds a plain fat JAR; adding one
 * or more rules turns it into a shaded JAR (packages rewritten). The presence of
 * rules is the only switch — there is no separate boolean.
 */
public abstract class ShadedJarExtension {
    /** {@code Main-Class} written into the fat jar's manifest. */
    public abstract Property<String> getMainClass();

    /** Filename classifier for the fat jar (default {@code all}). */
    public abstract Property<String> getArchiveClassifier();

    /** Package relocations: source dotted prefix -> shaded dotted prefix. */
    public abstract MapProperty<String, String> getRelocations();

    /**
     * Include patterns per relocation, keyed by the same {@code from} prefix as
     * {@link #getRelocations()}; comma-separated. See {@link RelocationSpec}.
     */
    public abstract MapProperty<String, String> getRelocationIncludes();

    /** Exclude patterns per relocation, same shape as {@link #getRelocationIncludes()}. */
    public abstract MapProperty<String, String> getRelocationExcludes();

    /** DSL sugar: {@code relocate 'com.google.common', 'shaded.guava'}. */
    public void relocate(String pattern, String destination) {
        getRelocations().put(pattern, destination);
    }

    /**
     * DSL sugar with a scoping block: {@code relocate 'from', 'to', { exclude '...' }}.
     * See {@link RelocationSpec}.
     */
    public void relocate(String pattern, String destination, Action<? super RelocationSpec> configure) {
        getRelocations().put(pattern, destination);
        List<String> includes = new ArrayList<>();
        List<String> excludes = new ArrayList<>();
        configure.execute(new RelocationSpec() {
            @Override public void include(String... patterns) {
                Collections.addAll(includes, patterns);
            }
            @Override public void exclude(String... patterns) {
                Collections.addAll(excludes, patterns);
            }
        });
        if (!includes.isEmpty()) getRelocationIncludes().put(pattern, String.join(",", includes));
        if (!excludes.isEmpty()) getRelocationExcludes().put(pattern, String.join(",", excludes));
    }
}
