# shaded-jar

A Gradle plugin that assembles **fat (uber) JARs** much faster than existing
tooling by (re)compressing every dependency in **parallel** on Gradle's worker
pool, instead of the single-threaded packaging that `Jar`/Shadow do today.

- Plugin id: `com.ljarocki.shaded-jar`
- Coordinates: `com.ljarocki:shaded-jar-plugin`

> **Phase 1 (MVP): fat JAR only — no package relocation yet.** Shading (parallel
> ASM relocation) is Phase 2. See [`../PROJECT.md`](../PROJECT.md) for the roadmap.

## Results

Sample app (`sample/`) bundling 11 real dependencies — guava, jackson, netty,
lucene, bouncycastle, protobuf, jetty, h2, postgres, commons-math3 →
**~19,700 entries, 32 MiB**. Archive step only (compile up-to-date, warm daemon,
fixed per-invocation overhead subtracted, best of 6):

| task                    | archive step | vs shaded-jar | output size |
| ----------------------- | -----------: | ------------: | ----------: |
| **fatJar** (shaded-jar) |  **1871 ms** |          1.0× |   33.83 MB  |
| shadowJar (Shadow)      |      3191 ms |         1.71× |   34.32 MB  |
| stockFatJar (Gradle Jar)|      2637 ms |         1.41× |   33.99 MB  |

shaded-jar is ~1.7× faster than Shadow here and produces the smallest jar.
Measured on Gradle 9.6.1, Shadow 9.5.1, JDK 21. Reproduce with
`bash benchmark.sh` (numbers scale with core count and dependency size); your
mileage varies.

## Usage

```gradle
plugins {
    id 'java'
    id 'com.ljarocki.shaded-jar'
}

shadedJar {
    mainClass = 'com.example.Main'   // written to the fat jar's manifest
    archiveClassifier = 'all'        // -> build/libs/<name>-<version>-all.jar
}
```

```
./gradlew fatJar
java -jar build/libs/<name>-<version>-all.jar
```

When the `java` plugin is applied, `fatJar` is auto-wired to the project's main
output plus its `runtimeClasspath`. You can also configure a `FatJarTask`
manually (`classpath`, `archiveFile`, `mainClass`, `manifestAttributes`, `level`,
`store`).

## How it works

1. **Enumerate sources** — project classes/resource dirs first (so their entries
   win duplicates), then each runtime dependency jar.
2. **Parallel pack** — one Gradle worker per source re-compresses its entries
   (raw DEFLATE) into a compact "part" file. This is the CPU-heavy stage that
   stock tooling runs single-threaded.
3. **Assemble** — a single thread streams the parts into one valid, reproducible
   JAR: first-wins duplicate handling, a freshly generated manifest, and
   stripping of dependency manifests, signature files (`*.SF/.DSA/.RSA/.EC`) and
   stale `INDEX.LIST`.

Timestamps are normalized, so output is byte-reproducible. The task is
`@CacheableTask` with `@Classpath` inputs → incremental (`UP-TO-DATE`) and
build-cache friendly (`FROM-CACHE`).

## Known limitations (Phase 1)

- **No relocation / shading** — Phase 2.
- **No Zip64** — fails fast with a clear error above 65,535 entries or 4 GiB.
- **No transformer/merge API** — service-file (`META-INF/services/*`) merging and
  Shadow-style transformers are Phase 2. Currently first-wins, so conflicting
  service files are not concatenated.
- Dependency entries are re-inflated then re-deflated. A future optimization is
  copying already-compressed DEFLATE streams verbatim from source jars.

## Layout

- `plugin/` — the plugin (`com.ljarocki.shaded-jar`), an included build.
- `sample/` — a runnable app applying shaded-jar + Shadow + a stock-Jar fat jar.
- `benchmark.sh` — timing harness.
