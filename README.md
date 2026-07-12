# shaded-jar

[![CI](https://github.com/Kukis13/shaded-jar/actions/workflows/ci.yml/badge.svg)](https://github.com/Kukis13/shaded-jar/actions/workflows/ci.yml)
[![Gradle 8 | 9](https://img.shields.io/badge/Gradle-8%20%7C%209-02303A?logo=gradle&logoColor=white)](plugin/src/test/java/com/ljarocki/shadedjar/GradleVersionCompatibilityFunctionalTest.java)
[![Configuration Cache](https://img.shields.io/badge/Configuration%20Cache-compatible-02303A?logo=gradle&logoColor=white)](plugin/src/test/java/com/ljarocki/shadedjar/ConfigurationCacheFunctionalTest.java)

A Gradle plugin that assembles **fat (uber) JARs and shaded JARs** much faster
than existing tooling, by doing the two CPU-heavy stages — DEFLATE compression
and ASM package relocation — **in parallel** on Gradle's worker pool, where
`Jar`/Shadow run them single-threaded.

- Plugin id: `com.ljarocki.shaded-jar`
- Coordinates: `com.ljarocki:shaded-jar-plugin`

Fat vs shaded is just configuration: **no `relocate(...)` rules → fat JAR; one or
more → shaded JAR.** There is no separate flag.

## Results

Sample app (`sample/`) bundling 11 real dependencies — guava, jackson, netty,
lucene, bouncycastle, protobuf, jetty, h2, postgres, commons-math3 →
**~19,700 entries, 32 MiB**, with Guava relocated to `com.example.shaded.guava`.
Archive step only (compile up-to-date, warm daemon, fixed per-invocation overhead
subtracted, best of 5):

| task                     | archive step | vs shaded-jar | output size |
| ------------------------ | -----------: | ------------: | ----------: |
| **fatJar** (shaded-jar)  |  **2077 ms** |          1.0× |   33.93 MB  |
| shadowJar (Shadow)       |      6410 ms |         3.09× |   34.38 MB  |
| stockFatJar (Gradle Jar)*|      2551 ms |            —  |   33.99 MB  |

shaded-jar is **~3× faster than Shadow** with relocation on, and produces the
smallest jar. Both `fatJar` and `shadowJar` relocate Guava and merge service
files; relocation is nearly free for shaded-jar because it runs inside the
per-source parallel workers, whereas it almost doubled Shadow's time. Measured on
Gradle 9.6.1, Shadow 9.5.1, JDK 21. (This table is always `--rerun-tasks` with
the pack cache cleared first, so it reflects a genuinely from-scratch archive
step — see "Pack cache" below for what a warm cache does on top of this.)

\* Stock Gradle `Jar` cannot relocate, so it's shown only as a fat-jar floor, not
a like-for-like shading comparison. Reproduce all of this with `bash benchmark.sh`.

### Single worker: algorithm vs parallelism

Forcing `--max-workers=1` isolates the core algorithm from the parallelism win.
Net archive step, best of 6, same sample:

| scenario                          | archive step |
| --------------------------------- | -----------: |
| shaded, all cores  (shaded-jar)   |     2077 ms  |
| shaded, 1 worker   (shaded-jar)   |     4284 ms  |
| shaded             (Shadow)       |     6410 ms  |
| fat, 1 worker      (shaded-jar)   |     1530 ms  |
| fat                (Gradle Jar)   |     2551 ms  |

- **vs Shadow, shaded-jar is faster even on one worker** (4284 vs 6410 ms, ~1.5×):
  the core packing/assembly is more efficient, and parallelism then adds ~2.06× on
  top (4284 → 2077 on 12 cores — sub-linear because assembly is single-threaded
  and a few large jars dominate the pack stage). This row is unchanged by the
  verbatim-copy optimization below, because relocation is on: every `.class` file
  still has to be inflated, rewritten by ASM, and re-deflated (a from-scratch
  archive step is unaffected by the pack cache too, for the same reason it's
  unaffected by verbatim-copy — see "Pack cache" below).
- **vs stock Gradle `Jar` (plain fat), shaded-jar is ~1.7× *faster* on one
  worker** (1530 vs 2551 ms) — a reversal from the ~1.2× slower it used to be
  before verbatim-copy. Plain fat jars (no relocation) copy every dependency
  entry's compressed DEFLATE stream straight out of its source jar's local
  file header, skipping the inflate/re-deflate round trip entirely (see "How
  it works" below); the per-source part-file intermediate is the only
  remaining overhead, so the lead over stock is no longer *purely*
  parallelism.

### Pack cache: cold vs warm rebuild

The numbers above are all `--rerun-tasks` with the pack cache cleared first —
a genuinely from-scratch archive step, same as before the cache existed. The
cache's own effect only shows up across *separate* builds where the
dependencies haven't changed (see "How it works"), so it's measured
separately: same shaded sample, cache cleared once, then two consecutive
`fatJar` runs.

| scenario                  | archive step (wall) | net of fixed overhead |
| -------------------------- | -------------------: | ---------------------: |
| shaded, pack-cache cold    |              3547 ms |                2333 ms |
| shaded, pack-cache warm    |              2515 ms |                1301 ms |

That's **~1.8× net**, measured as a full, separate `gradle :sample:fatJar`
process invocation each time — the same wall-clock methodology as every other
row on this page. The plugin's own internal log line for the same two runs
tells a more dramatic story about *where* that time went:

```
pack=823ms assemble=280ms TOTAL=1103ms  (pack-cache: 0 hit, 57 miss)
pack=20ms  assemble=312ms TOTAL=332ms   (pack-cache: 57 hit, 0 miss)
```

Packing itself drops **823 ms → 20 ms** (all 57 dependency jars hit the
cache; only the project's own output still gets packed) — a ~3.3× drop in the
task's own execution time. The gap between that and the ~1.8× *process*
number is real, not measurement error: a fresh `gradle` invocation also pays
Gradle's own per-task configuration cost (evaluating `build.gradle`,
resolving `runtimeClasspath`, snapshotting the `@Classpath` input) every
time, and none of that shrinks just because packing got cheap — it's outside
what the pack cache touches. A live dev daemon doing a normal edit-compile-run
loop (rather than a fresh `--rerun-tasks` process per measurement) pays much
less of that fixed cost already, so day-to-day incremental rebuilds should
land closer to the task-internal number than the cold-process one.

## Usage

Fat JAR (no relocation):

```gradle
plugins {
    id 'java'
    id 'com.ljarocki.shaded-jar'
}

shadedJar {
    mainClass = 'com.example.Main'
    archiveClassifier = 'all'        // -> build/libs/<name>-<version>-all.jar
}
```

Shaded JAR — add relocation rules:

```gradle
shadedJar {
    mainClass = 'com.example.Main'
    relocate 'com.google.common', 'com.example.shaded.guava'
    relocate 'org.apache.commons', 'com.example.shaded.commons'
}
```

Scope a relocation to part of a package with an `include`/`exclude` block —
e.g. to leave a public-API type or a reflectively-scanned annotation under its
original name while relocating the rest:

```gradle
shadedJar {
    relocate 'com.google.common', 'com.example.shaded.guava', {
        exclude 'com.google.common.annotations.**'
    }
}
```

`include`/`exclude` take a small pattern language, not full Ant/Shadow-style
globs: either an exact dotted class name (`'com.google.common.collect.ImmutableList'`)
or a prefix wildcard (`'com.google.common.annotations.**'`, matching that
package and everything nested under it). Excludes always win over includes;
if any `include` patterns are given, only names matching at least one of them
are relocated by that rule at all. A name a rule's prefix matches but its
filter rejects falls through to the next (shorter-prefix) rule instead of
being left alone outright, so an exclude can "un-relocate" a subpackage
nested inside a broader rule.

```
./gradlew fatJar
java -jar build/libs/<name>-<version>-all.jar
```

When the `java` plugin is applied, `fatJar` is auto-wired to the project's main
output plus its `runtimeClasspath`. The pack stage runs on Gradle's worker pool,
so concurrency follows the usual `--max-workers` / `org.gradle.workers.max`
(use `--max-workers=1` to force it sequential).

## How it works

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
   dependency was previously re-processed anyway. On the sample app (58
   sources, relocation on), the task's own pack phase drops from ~820 ms to
   ~20 ms once the cache is warm; end-to-end, measured as a full separate
   `gradle` process each time, that's a **~1.8× drop** (see "Pack cache: cold
   vs warm rebuild" below for the full breakdown and why those two numbers
   differ). The cache lives under Gradle's user home
   (`~/.gradle/caches/shaded-jar/`), not the project's `build/`
   directory, specifically so it survives fresh checkouts on CI when
   `~/.gradle/caches` is restored — which `gradle/actions/setup-gradle` and
   `actions/setup-java`'s `cache: gradle` option both already do by default,
   so most CI setups get this for free with no extra configuration.
3. **Assemble** — a single thread streams the parts into one valid, reproducible
   JAR: first-wins duplicate handling, **`META-INF/services/*` and the Spring
   properties files merged** across all sources (see the table below), a
   freshly generated manifest, and stripping of dependency manifests,
   signature files (`*.SF/.DSA/.RSA/.EC`) and stale `INDEX.LIST`. Any entry, or
   the central directory itself, that doesn't fit the classic ZIP format's
   32-bit/16-bit fields transparently promotes to Zip64. If any source
   contributes a multi-release-JAR override (`META-INF/versions/N/...`), the
   generated manifest gets `Multi-Release: true` — without it the JVM ignores
   that directory outright — and relocation applies to the override the same
   way it does to the base class (see Known limitations).

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

## Known limitations

- **The pack cache only applies to jar sources**, never a project's own
  compiled output directory — that changes on essentially every build in the
  scenario it targets, so caching it would buy nothing but hashing overhead.
  It's capped at 1 GiB total (least-recently-used entries evicted first) and
  isn't user-configurable yet (no way to change the cap, location, or turn it
  off). Its cache key covers source content and every packing parameter
  (level, store, relocations, includes/excludes), but **not the plugin's own
  version** — entries are only invalidated across a plugin upgrade if that
  release bumps `PackCache.SCHEMA_VERSION` (a manually-maintained constant,
  currently `v1`). A future release that changes packing behavior without
  bumping it could silently serve a stale entry for an unchanged dependency.
- **Resource transformers cover `META-INF/services/*` and the three Spring
  properties files** (`spring.factories`/`.handlers`/`.schemas`). Other
  formats with their own merge semantics — Spring Boot's binary-adjacent
  config metadata, log4j2's binary plugin cache (`Log4j2Plugins.dat`),
  HOCON/`reference.conf` (Akka/Lightbend Config) — aren't handled yet; a
  dependency relying on one of those needs its merge/relocation handled
  another way for now.
- **`spring.schemas` values are never relocated** — they're classpath resource
  paths (e.g. `META-INF/some.xsd`), not class names, so package-prefix
  relocation doesn't apply to them the way it does to `spring.factories`/
  `.handlers` values.
- **Zip64 covers the output archive**: more than 65,534 entries, or any entry/
  central-directory size or offset past 4 GiB, transparently promotes to Zip64
  extra fields and a Zip64 End Of Central Directory record + locator — no size
  cliff, no separate flag. It does *not* yet cover *reading* a Zip64-format
  source dependency jar for the verbatim-copy fast path below (that case just
  falls back to the normal decode/recompress path for that one jar) — an
  already-Zip64 dependency is rare enough that this is a minor missed
  optimization, not a correctness gap.
- **Relocation is prefix-based**, applied to class bytecode, entry paths, string
  constants, and service files, optionally scoped per rule with `include`/
  `exclude` (see Usage above — a small pattern subset, not full Ant globs).
- **Multi-release JAR (`META-INF/versions/N/...`) awareness**: a versioned
  override relocates the same way its base class does (prefix stripped,
  mapped, reattached), and the output manifest gets `Multi-Release: true`
  whenever any source contributes one. This covers `.class` overrides and
  ordinary resources under a `versions/N/` directory; it does *not* special-
  case a `META-INF/services/*` or Spring properties file that itself happens
  to live under `versions/N/` (an unusual pattern) — that falls through to
  being treated as a plain (correctly relocated and path-preserved, just not
  merged) resource instead.
- **String-constant relocation is best-effort** (prefix match), like Shadow — a
  literal that merely starts with a relocated package but isn't a class name
  would also be rewritten.
- **Verbatim-copied entries keep the source jar's original DEFLATE level**,
  not the configured `level`, since their compressed bytes are never touched.
  The copy also only applies to source jars with a plain (non-Zip64) central
  directory; anything else — or any entry with an unreadable local header —
  transparently falls back to the normal decode/recompress path, so this is a
  pure performance optimization with no effect on correctness.

## Compatibility

- **Gradle 8 and 9** — `GradleVersionCompatibilityFunctionalTest` runs a real
  fat/shaded build against Gradle 8.5 and 9.6.1 explicitly (via TestKit's
  `withGradleVersion`, independent of whatever version the wrapper pins).
  8.5, not 8.0, is the actual floor tested: it's the earliest Gradle 8.x
  release that runs on a JDK 21 daemon, which is what this project's CI uses.
  The plugin's own bytecode targets Java 8 and only uses Provider/Worker APIs
  stable since well before Gradle 8, so nothing here suggests true 8.0–8.4
  would behave differently — it's just genuinely untested, rather than "known
  to work."
- **Configuration cache** — `ConfigurationCacheFunctionalTest` runs `fatJar
  --configuration-cache` twice per Gradle version above: once to store the
  cache, once more (no changes) to confirm it's actually *reused*, not just
  that the first run didn't complain. Both major versions pass with no
  reported problems.

## Development

```
./gradlew -p plugin test     # unit (Relocator) + functional (TestKit) tests
./gradlew :sample:fatJar     # build the sample shaded jar
bash benchmark.sh            # local timing harness (uses `gradle`; GRADLE=./gradlew to pin)
```

CI ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)) runs the tests on every
push and PR, and the benchmark on pushes to `main` (results land in the run's job
summary).

## Layout

- `plugin/` — the plugin (`com.ljarocki.shaded-jar`), an included build.
  - `src/test/` — `RelocatorTest` (hermetic bytecode/path/service tests,
    including include/exclude filtering and rule fallthrough),
    `SourcePackerTest` (verbatim compressed-stream copy), `Zip64SupportTest`
    (hermetic Zip64 byte-layout tests), `SpringPropertiesTest` (hermetic
    spring.factories/.handlers/.schemas merge + relocation semantics),
    `PackCacheTest` (hermetic: key computation, atomic store, LRU eviction),
    `PluginFunctionalTest`, `Zip64EntryCountFunctionalTest`,
    `SpringPropertiesFunctionalTest`, `RelocationFilterFunctionalTest`,
    `MultiReleaseJarFunctionalTest`, `GradleVersionCompatibilityFunctionalTest`,
    `ConfigurationCacheFunctionalTest`, and `PackCacheFunctionalTest` (TestKit:
    builds and runs real fat/shaded/Zip64-scale/Spring-Boot-flavored/filtered-
    relocation/multi-release jars; the middle two explicitly against both
    Gradle 8.5 and 9.6.1; the last proves the pack cache is actually reused
    across two separate project checkouts sharing a persisted Gradle user
    home — the CI scenario).
- `sample/` — a runnable app applying shaded-jar + Shadow + a stock-Jar fat jar,
  demonstrating relocation and service merging.
- `benchmark.sh` — timing harness.
