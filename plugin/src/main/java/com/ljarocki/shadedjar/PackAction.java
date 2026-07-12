package com.ljarocki.shadedjar;

import org.gradle.workers.WorkAction;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thin Gradle Worker-API wrapper around {@link SourcePacker}: one work item packs
 * one source (a dependency jar or a classes directory) into a part file. Gradle
 * runs these on its shared worker pool, so concurrency is bounded by
 * {@code --max-workers} / {@code org.gradle.workers.max} and coordinated with the
 * rest of the build.
 *
 * <p>For a jar source (never a directory — a project's own compiled output
 * changes on essentially every build in the scenario the cache targets, so
 * there's nothing to gain caching it), checks {@link PackCache} first: a hit
 * copies the previously-packed bytes straight to the part-file location instead
 * of re-running {@link SourcePacker}; a miss packs normally, then stores the
 * result for next time. Any cache read/write hiccup falls back to the always-
 * correct uncached path — caching is a pure optimization and must never be able
 * to fail or corrupt a build.
 */
public abstract class PackAction implements WorkAction<PackParams> {

    // Shared across work items within one FatJarTask execution (noIsolation runs
    // in-process); FatJarTask reads and resets these after queue.await().
    static final AtomicInteger CACHE_HITS = new AtomicInteger();
    static final AtomicInteger CACHE_MISSES = new AtomicInteger();

    @Override
    public void execute() {
        PackParams p = getParameters();
        File source = p.getSource().getAsFile().get();
        File part = p.getPart().getAsFile().get();

        String cacheKey = null;
        File cacheDir = null;
        if (source.isFile() && p.getPackCacheDir().isPresent()) {
            cacheDir = p.getPackCacheDir().getAsFile().get();
            try {
                byte[] fingerprint = PackCache.configFingerprint(p.getLevel().get(), p.getStore().get(),
                        p.getRelocations().get(), p.getRelocationIncludes().get(), p.getRelocationExcludes().get());
                cacheKey = PackCache.cacheKey(fingerprint, source);
                File cached = PackCache.find(cacheDir, cacheKey);
                if (cached != null) {
                    Files.copy(cached.toPath(), part.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    CACHE_HITS.incrementAndGet();
                    return;
                }
            } catch (IOException hashingHiccup) {
                cacheKey = null; // still pack normally below; just skip caching this one source
            }
        }

        pack(p, source, part);

        if (cacheKey != null) {
            try {
                PackCache.store(cacheDir, cacheKey, part);
            } catch (IOException ignored) {
                // Persisting the cache entry is optional; the build itself already succeeded.
            }
            CACHE_MISSES.incrementAndGet();
        }
    }

    private static void pack(PackParams p, File source, File part) {
        SourcePacker packer = new SourcePacker(
                p.getLevel().get(), p.getStore().get(), p.getRelocations().get(),
                p.getRelocationIncludes().get(), p.getRelocationExcludes().get());
        try {
            packer.pack(source, part);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
