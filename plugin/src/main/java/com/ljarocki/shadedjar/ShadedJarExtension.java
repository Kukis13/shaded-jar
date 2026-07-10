package com.ljarocki.shadedjar;

import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;

/**
 * DSL for the plugin:
 *
 * <pre>
 *   shadedJar {
 *       mainClass = 'com.example.Main'
 *       archiveClassifier = 'all'
 *       relocate 'com.google.common', 'com.example.shaded.guava'   // optional
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

    /** Max worker threads for packing; default = CPU count, {@code 1} = sequential. */
    public abstract Property<Integer> getThreads();

    /** DSL sugar: {@code relocate 'com.google.common', 'shaded.guava'}. */
    public void relocate(String pattern, String destination) {
        getRelocations().put(pattern, destination);
    }
}
