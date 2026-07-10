package com.ljarocki.shadedjar;

import org.gradle.workers.WorkAction;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Thin Gradle Worker-API wrapper around {@link SourcePacker}: one work item packs
 * one source (a dependency jar or a classes directory) into a part file. Gradle
 * runs these on its shared worker pool, so concurrency is bounded by
 * {@code --max-workers} / {@code org.gradle.workers.max} and coordinated with the
 * rest of the build.
 */
public abstract class PackAction implements WorkAction<PackParams> {

    @Override
    public void execute() {
        PackParams p = getParameters();
        SourcePacker packer = new SourcePacker(
                p.getLevel().get(), p.getStore().get(), p.getRelocations().get(),
                p.getRelocationIncludes().get(), p.getRelocationExcludes().get());
        try {
            packer.pack(p.getSource().getAsFile().get(), p.getPart().getAsFile().get());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
