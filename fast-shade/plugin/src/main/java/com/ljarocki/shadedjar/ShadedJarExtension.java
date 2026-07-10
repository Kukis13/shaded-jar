package com.ljarocki.shadedjar;

import org.gradle.api.provider.Property;

/**
 * DSL for the plugin: {@code shadedJar { mainClass = '...'; archiveClassifier = 'all' }}.
 */
public abstract class ShadedJarExtension {
    /** {@code Main-Class} written into the fat jar's manifest. */
    public abstract Property<String> getMainClass();

    /** Filename classifier for the fat jar (default {@code all}). */
    public abstract Property<String> getArchiveClassifier();
}
