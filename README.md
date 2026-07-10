# shaded-jar

[![CI](https://github.com/Kukis13/shaded-jar/actions/workflows/ci.yml/badge.svg)](https://github.com/Kukis13/shaded-jar/actions/workflows/ci.yml)

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
| **fatJar** (shaded-jar)  |  **1807 ms** |          1.0× |   33.82 MB  |
| shadowJar (Shadow)       |      5563 ms |         3.08× |   34.38 MB  |
| stockFatJar (Gradle Jar)*|      2431 ms |            —  |   33.99 MB  |

shaded-jar is **~3× faster than Shadow** with relocation on, and produces the
smallest jar. Both `fatJar` and `shadowJar` relocate Guava and merge service
files; relocation is nearly free for shaded-jar because it runs inside the
per-source parallel workers, whereas it almost doubled Shadow's time. Measured on
Gradle 9.6.1, Shadow 9.5.1, JDK 21.

\* Stock Gradle `Jar` cannot relocate, so it's shown only as a fat-jar floor, not
a like-for-like shading comparison. Reproduce all of this with `bash benchmark.sh`.

### Single worker: algorithm vs parallelism

Forcing `--max-workers=1` isolates the core algorithm from the parallelism win.
Net archive step, best of 6, same sample:

| scenario                          | archive step |
| --------------------------------- | -----------: |
| shaded, all cores  (shaded-jar)   |     1807 ms  |
| shaded, 1 worker   (shaded-jar)   |     3542 ms  |
| shaded             (Shadow)       |     5563 ms  |
| fat, 1 worker      (shaded-jar)   |     1399 ms  |
| fat                (Gradle Jar)   |     2431 ms  |

- **vs Shadow, shaded-jar is faster even on one worker** (3542 vs 5563 ms, ~1.6×):
  the core packing/assembly is more efficient, and parallelism then adds ~1.96× on
  top (3542 → 1807 on 12 cores — sub-linear because assembly is single-threaded
  and a few large jars dominate the pack stage). This row is unchanged by the
  verbatim-copy optimization below, because relocation is on: every `.class` file
  still has to be inflated, rewritten by ASM, and re-deflated.
- **vs stock Gradle `Jar` (plain fat), shaded-jar is now ~1.7× *faster* on one
  worker** (1399 vs 2431 ms) — a reversal from the ~1.2× slower it used to be.
  Plain fat jars (no relocation) now copy every dependency entry's compressed
  DEFLATE stream straight out of its source jar's local file header, skipping
  the inflate/re-deflate round trip entirely (see "How it works" below); the
  per-source part-file intermediate is the only remaining overhead, so the lead
  over stock is no longer *purely* parallelism.

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
3. **Assemble** — a single thread streams the parts into one valid, reproducible
   JAR: first-wins duplicate handling, **`META-INF/services/*` and the Spring
   properties files merged** across all sources (see the table below), a
   freshly generated manifest, and stripping of dependency manifests,
   signature files (`*.SF/.DSA/.RSA/.EC`) and stale `INDEX.LIST`. Any entry, or
   the central directory itself, that doesn't fit the classic ZIP format's
   32-bit/16-bit fields transparently promotes to Zip64.

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
  constants, and service files. Per-relocation `include`/`exclude` filters and
  multi-release-jar (`META-INF/versions/*`) awareness are not implemented yet.
- **String-constant relocation is best-effort** (prefix match), like Shadow — a
  literal that merely starts with a relocated package but isn't a class name
  would also be rewritten.
- **Verbatim-copied entries keep the source jar's original DEFLATE level**,
  not the configured `level`, since their compressed bytes are never touched.
  The copy also only applies to source jars with a plain (non-Zip64) central
  directory; anything else — or any entry with an unreadable local header —
  transparently falls back to the normal decode/recompress path, so this is a
  pure performance optimization with no effect on correctness.

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
  - `src/test/` — `RelocatorTest` (hermetic bytecode/path/service tests),
    `SourcePackerTest` (verbatim compressed-stream copy), `Zip64SupportTest`
    (hermetic Zip64 byte-layout tests), `SpringPropertiesTest` (hermetic
    spring.factories/.handlers/.schemas merge + relocation semantics),
    `PluginFunctionalTest`, `Zip64EntryCountFunctionalTest`, and
    `SpringPropertiesFunctionalTest` (TestKit: builds and runs real fat/
    shaded/Zip64-scale/Spring-Boot-flavored jars).
- `sample/` — a runnable app applying shaded-jar + Shadow + a stock-Jar fat jar,
  demonstrating relocation and service merging.
- `benchmark.sh` — timing harness.
