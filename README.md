# shaded-jar

[![CI](https://github.com/Kukis13/shaded-jar/actions/workflows/ci.yml/badge.svg)](https://github.com/Kukis13/shaded-jar/actions/workflows/ci.yml)
[![Gradle 8 | 9](https://img.shields.io/badge/Gradle-8%20%7C%209-02303A?logo=gradle&logoColor=white)](docs/COMPATIBILITY.md)
[![Configuration Cache](https://img.shields.io/badge/Configuration%20Cache-compatible-02303A?logo=gradle&logoColor=white)](docs/COMPATIBILITY.md)

A Gradle plugin that assembles **fat (uber) JARs and shaded JARs** much faster
than existing tooling, by doing the two CPU-heavy stages — DEFLATE compression
and ASM package relocation — **in parallel** on Gradle's worker pool, where
`Jar`/Shadow run them single-threaded.

- Plugin id: `com.ljarocki.shaded-jar` (apply via the `plugins {}` block —
  resolved from the [Gradle Plugin Portal](https://plugins.gradle.org),
  not a Maven Central artifact)

Fat vs shaded is just configuration: **no `relocate(...)` rules → fat JAR; one or
more → shaded JAR.** There is no separate flag.

## Results

Sample app bundling 11 real dependencies (~19,700 entries, 32 MiB), Guava
relocated. Archive step only, best of 5:

| task                      | archive step | vs shaded-jar |
| -------------------------- | -----------: | -------------: |
| **fatJar** (shaded-jar)   |  **2077 ms** |           1.0× |
| shadowJar (Shadow)        |      6410 ms |          3.09× |
| stockFatJar (Gradle Jar)* |      2551 ms |             —  |

**~3× faster than com.gradleup.shadow** with relocation on, and the smallest output.
\* Stock Gradle `Jar` can't relocate — shown only as a fat-jar floor.

Full breakdown (single-worker isolation, pack-cache cold/warm, methodology) →
**[docs/BENCHMARKS.md](docs/BENCHMARKS.md)**.

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

Drop unreferenced dependency classes with `minimize()` — anything the
reachability analysis can't prove is used by the project's own code is left
out of the jar entirely:

```gradle
shadedJar {
    minimize()
    minimize {
        // Only ever looked up by name (reflection/ServiceLoader), so static
        // reachability can't see it's actually used — keep it regardless.
        keep 'com.example.plugin.ReflectivelyLoadedThing'
        keep 'org.some.plugin.**'
    }
}
```

`keep` takes the same pattern language as relocation's `include`/`exclude`
(exact dotted name, or a `.**` prefix wildcard). Like Shadow's own
`minimize()`, this can't see through reflection, `ServiceLoader`-by-name, or
`Class.forName` — a class only ever found that way needs an explicit `keep`.

```
./gradlew fatJar
java -jar build/libs/<name>-<version>-all.jar
```

When the `java` plugin is applied, `fatJar` is auto-wired to the project's main
output plus its `runtimeClasspath`. The pack stage runs on Gradle's worker pool,
so concurrency follows the usual `--max-workers` / `org.gradle.workers.max`
(use `--max-workers=1` to force it sequential).

## Learn more

- **[How it works](docs/ARCHITECTURE.md)** — the pack/assemble pipeline,
  service-file and Spring-properties merging, the pack cache.
- **[Benchmarks](docs/BENCHMARKS.md)** — full results, single-worker
  breakdown, pack-cache cold vs warm.
- **[Known limitations](docs/LIMITATIONS.md)**
- **[Compatibility](docs/COMPATIBILITY.md)** — Gradle 8/9, configuration cache.
- **[Development](docs/DEVELOPMENT.md)** — running tests/benchmarks, repo layout.
