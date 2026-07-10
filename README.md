# shaded-jar

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
| **fatJar** (shaded-jar)  |  **2018 ms** |          1.0× |   33.93 MB  |
| shadowJar (Shadow)       |      5859 ms |         2.90× |   34.38 MB  |
| stockFatJar (Gradle Jar)*|      2586 ms |            —  |   33.99 MB  |

shaded-jar is **~2.9× faster than Shadow** with relocation on, and produces the
smallest jar. Both `fatJar` and `shadowJar` relocate Guava and merge service
files; relocation is nearly free for shaded-jar (2018 ms vs 1871 ms without it)
because it happens inside the per-source parallel workers, whereas it almost
doubled Shadow's time. Measured on Gradle 9.6.1, Shadow 9.5.1, JDK 21.

\* Stock Gradle `Jar` cannot relocate, so it's shown only as a fat-jar floor, not
a like-for-like shading comparison. Reproduce all of this with `bash benchmark.sh`.

### Single thread: algorithm vs parallelism

Setting `threads = 1` isolates the core algorithm from the parallelism win. Net
archive step, best of 5, same sample:

| scenario                         | archive step |
| -------------------------------- | -----------: |
| shaded, all cores  (shaded-jar)  |     1931 ms  |
| shaded, 1 thread   (shaded-jar)  |     3691 ms  |
| shaded             (Shadow)      |     5679 ms  |
| fat, 1 thread      (shaded-jar)  |     3075 ms  |
| fat                (Gradle Jar)  |     2488 ms  |

- **vs Shadow, shaded-jar is faster even on one thread** (3691 vs 5679 ms, ~1.5×):
  the core packing/assembly is more efficient, and parallelism then adds ~1.9× on
  top (3691 → 1931 on 12 cores — sub-linear because assembly is single-threaded
  and a few large jars dominate the pack stage).
- **vs stock Gradle `Jar` (plain fat), shaded-jar is ~1.2× *slower* on one thread**
  (3075 vs 2488 ms). Our per-source "part file" intermediate (write to disk, read
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
    // threads = 1   // optional: cap the pack pool (default = CPU count)
}
```

```
./gradlew fatJar
java -jar build/libs/<name>-<version>-all.jar
```

When the `java` plugin is applied, `fatJar` is auto-wired to the project's main
output plus its `runtimeClasspath`. The pack stage runs on an internal thread pool
sized by `threads` (default = available processors, `1` = fully sequential).

## How it works

1. **Enumerate sources** — project classes/resource dirs first (so their entries
   win duplicates), then each runtime dependency jar.
2. **Parallel pack** — one task per source, on an internal fixed thread pool
   (size = `threads`), (re)compresses its entries and, if relocation rules are set,
   rewrites each class with ASM's `ClassRemapper` (type references, descriptors,
   string constants) and moves its entry path.
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

## Layout

- `plugin/` — the plugin (`com.ljarocki.shaded-jar`), an included build.
- `sample/` — a runnable app applying shaded-jar + Shadow + a stock-Jar fat jar,
  demonstrating relocation and service merging.
- `benchmark.sh` — timing harness.
