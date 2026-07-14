# How it works

1. **Enumerate sources** — project classes/resource dirs first (so their entries
   win duplicates), then each runtime dependency jar.
2. **Parallel pack** — one Gradle worker per source (bounded by `--max-workers`)
   (re)compresses its entries and, if relocation rules are set, rewrites each class
   with ASM's `ClassRemapper` (type references, descriptors, string constants) and
   moves its entry path.
   This is the CPU-heavy stage stock tooling runs single-threaded.
   For a dependency entry whose bytes won't change — always true in a plain fat
   jar, and true for any non-class, non-service entry in a shaded jar too, since
   relocation only ever renames those, never rewrites their content — the worker
   copies the already-DEFLATE'd bytes straight out of the source jar's local file
   header instead of inflating and re-deflating them. Class files still always go
   through ASM whenever any relocation is configured, even if their own path
   isn't relocated, because their bytecode may reference a class that is.

   On top of that, each jar source's packed output is looked up in a
   **persistent, cross-build pack cache** first, keyed by the source jar's
   content hash *and* every packing parameter that affects the output (level,
   store, relocations, includes/excludes) — so a config change can never
   silently reuse a stale entry with the old package names. A hit skips
   packing (and, for a shaded jar, ASM) entirely for that source; a miss packs
   normally and stores the result for next time. This matters most for shaded
   jars specifically: relocation forces *every* class in *every* dependency
   through ASM regardless of whether verbatim-copy applies, so on an
   incremental rebuild where only your own code changed, every unchanged
   dependency was previously re-processed anyway. See
   [Benchmarks](BENCHMARKS.md#pack-cache-cold-vs-warm-rebuild) for cold vs
   warm numbers. The cache lives under Gradle's user home
   (`~/.gradle/caches/shaded-jar/`), not the project's `build/`
   directory, specifically so it survives fresh checkouts on CI when
   `~/.gradle/caches` is restored — which `gradle/actions/setup-gradle` and
   `actions/setup-java`'s `cache: gradle` option both already do by default,
   so most CI setups get this for free with no extra configuration.
3. **Assemble** — a single thread streams the parts into one valid, reproducible
   JAR: first-wins duplicate handling, **`META-INF/services/*`, the Spring
   properties files, and log4j2's binary plugin cache merged** across all
   sources (see the table below), a freshly generated manifest, and stripping
   of dependency manifests,
   signature files (`*.SF/.DSA/.RSA/.EC`) and stale `INDEX.LIST`. Any entry, or
   the central directory itself, that doesn't fit the classic ZIP format's
   32-bit/16-bit fields transparently promotes to Zip64. If any source
   contributes a multi-release-JAR override (`META-INF/versions/N/...`), the
   generated manifest gets `Multi-Release: true` — without it the JVM ignores
   that directory outright — and relocation applies to the override the same
   way it does to the base class (see [Known limitations](LIMITATIONS.md)).

Service-file merging is **on by default** (Shadow requires an explicit
`mergeServiceFiles()`), so `ServiceLoader`-based libraries — JDBC drivers, Jackson
modules, etc. — keep working in the merged jar. The three well-known Spring
resource files get the same treatment, but with the merge semantics they
actually need instead of line-concatenation:

| file                        | merge                                    | relocated                        |
| ---------------------------- | ----------------------------------------- | --------------------------------- |
| `META-INF/spring.factories`  | comma-append + dedup per key (a real list) | key **and** each listed value      |
| `META-INF/spring.handlers`   | first-wins per key (a real conflict otherwise) | value only (key is a URI)     |
| `META-INF/spring.schemas`    | first-wins per key                        | neither (key/value are resource IDs, not classes) |

`spring.factories` merges this way because that's exactly how Spring's own
`SpringFactoriesLoader` merges multiple copies of the file across a normal
(unshaded) classpath — a fat jar just has to do at build time what would
otherwise happen at runtime. `spring.handlers`/`.schemas` have no such
multi-value meaning, so a genuine same-key conflict between two dependencies
(different values, not just the same file included twice) is logged as a
build warning rather than silently dropped — the first one (in classpath
order) still wins, but you'll know about it. Timestamps are normalized, so
output is byte-reproducible. The task is `@CacheableTask` with `@Classpath`
inputs → incremental (`UP-TO-DATE`) and build-cache friendly (`FROM-CACHE`).

`Log4j2Plugins.dat` — log4j2's own annotation processor generates this
binary plugin-discovery cache, and without a real merge, bundling more than
one log4j2-using dependency silently drops every plugin but one's. Rather
than reimplement that binary format, `Log4j2PluginCacheMerger` reuses
log4j-core's own `PluginCache`/`PluginEntry` classes directly (an
`implementation` dependency of this plugin, never bundled into a consuming
project's jar) to load every source's cache, merge them into one, relocate
each plugin's declared class name if it matches a relocation rule, and
write the result back out as a single entry.

See also: [Benchmarks](BENCHMARKS.md) · [Known limitations](LIMITATIONS.md) ·
[Compatibility](COMPATIBILITY.md) · [Development](DEVELOPMENT.md)
