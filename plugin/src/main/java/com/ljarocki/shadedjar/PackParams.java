package com.ljarocki.shadedjar;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.workers.WorkParameters;

/** Parameters for packing one source (a dependency jar or a classes directory). */
public interface PackParams extends WorkParameters {
    /** The source jar file or classes/resource directory. */
    RegularFileProperty getSource();

    /** Output part file this worker writes. */
    RegularFileProperty getPart();

    /** DEFLATE level, or -1 for the zlib default (matches Gradle's stock Zip). */
    Property<Integer> getLevel();

    /** When true, STORE every entry instead of DEFLATE (fastest, larger output). */
    Property<Boolean> getStore();

    /** Package relocations (source dotted prefix -> shaded dotted prefix); empty = fat jar. */
    MapProperty<String, String> getRelocations();

    /** Include patterns per relocation, keyed by the same prefix as {@link #getRelocations()}. */
    MapProperty<String, String> getRelocationIncludes();

    /** Exclude patterns per relocation, same shape as {@link #getRelocationIncludes()}. */
    MapProperty<String, String> getRelocationExcludes();

    /** Persistent cross-build cache directory (see {@link PackCache}); optional — absent disables caching for this source. */
    DirectoryProperty getPackCacheDir();
}
