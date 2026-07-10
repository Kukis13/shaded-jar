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
| **fatJar** (shaded-jar)  |  **1870 ms** |          1.0× |   33.93 MB  |
| shadowJar (Shadow)       |      5649 ms |         3.02× |   34.38 MB  |
| stockFatJar (Gradle Jar)*|      2495 ms |            —  |   33.99 MB  |

shaded-jar is **~3× faster than Shadow** with relocation on, and produces the
smallest jar. Both `fatJar` and `shadowJar` relocate Guava and merge service
files; relocation is nearly free for shaded-jar because it runs inside the
per-source parallel workers, whereas it almost doubled Shadow's time. Measured on
Gradle 9.6.1, Shadow 9.5.1, JDK 21.

\* Stock Gradle `Jar` cannot relocate, so it's shown only as a fat-jar floor, not
a like-for-like shading comparison. Reproduce all of this with `bash benchmark.sh`.

### Single worker: algorithm vs parallelism

Forcing `--max-workers=1` isolates the core algorithm from the parallelism win.
Net archive step, best of 5, same sample:

| scenario                          | archive step |
| --------------------------------- | -----------: |
| shaded, all cores  (shaded-jar)   |     1870 ms  |
| shaded, 1 worker   (shaded-jar)   |     3653 ms  |
| shaded             (Shadow)       |     5649 ms  |
| fat, 1 worker      (shaded-jar)   |     3072 ms  |
| fat                (Gradle Jar)   |     2495 ms  |

- **vs Shadow, shaded-jar is faster even on one worker** (3653 vs 5649 ms, ~1.5×):
  the core packing/assembly is more efficient, and parallelism then adds ~1.95× on
  top (3653 → 1870 on 12 cores — sub-linear because assembly is single-threaded
  and a few large jars dominate the pack stage).
- **vs stock Gradle `Jar` (plain fat), shaded-jar is ~1.2× *slower* on one worker**
  (3072 vs 2495 ms). Our per-source "part file" intermediate (write to disk, read
  back) plus full re-inflate/re-deflate cost more single-threaded than Gradle's
  direct streaming — so the lead over stock is *purely* parallelism. Copying
  already-compressed DEFLATE streams verbatim (a planned optimization) would close
  this gap.

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
3. **Assemble** — a single thread streams the parts into one valid, reproducible
   JAR: first-wins duplicate handling, **`META-INF/services/*` merged** across all
   sources (deduped), a freshly generated manifest, and stripping of dependency
   manifests, signature files (`*.SF/.DSA/.RSA/.EC`) and stale `INDEX.LIST`.

Service-file merging is **on by default** (Shadow requires an explicit
`mergeServiceFiles()`), so `ServiceLoader`-based libraries — JDBC drivers, Jackson
modules, etc. — keep working in the merged jar. Timestamps are normalized, so
output is byte-reproducible. The task is `@CacheableTask` with `@Classpath`
inputs → incremental (`UP-TO-DATE`) and build-cache friendly (`FROM-CACHE`).

## Known limitations

- **No Zip64** — fails fast with a clear error above 65,535 entries or 4 GiB.
- **Relocation is prefix-based**, applied to class bytecode, entry paths, string
  constants, and service files. Per-relocation `include`/`exclude` filters and
  multi-release-jar (`META-INF/versions/*`) awareness are not implemented yet.
- **String-constant relocation is best-effort** (prefix match), like Shadow — a
  literal that merely starts with a relocated package but isn't a class name
  would also be rewritten.
- Dependency entries are re-inflated then re-deflated; copying already-compressed
  DEFLATE streams verbatim is a future optimization.

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
  - `src/test/` — `RelocatorTest` (hermetic bytecode/path/service tests) and
    `PluginFunctionalTest` (TestKit: builds and runs real fat/shaded jars).
- `sample/` — a runnable app applying shaded-jar + Shadow + a stock-Jar fat jar,
  demonstrating relocation and service merging.
- `benchmark.sh` — timing harness.
