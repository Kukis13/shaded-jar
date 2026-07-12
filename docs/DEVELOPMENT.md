# Development

```
./gradlew -p plugin test     # unit (Relocator) + functional (TestKit) tests
./gradlew :sample:fatJar     # build the sample shaded jar
bash benchmark.sh            # local timing harness (uses `gradle`; GRADLE=./gradlew to pin)
```

CI ([`.github/workflows/ci.yml`](../.github/workflows/ci.yml)) runs the tests on every
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
- `docs/` — this file, plus [architecture](ARCHITECTURE.md),
  [benchmarks](BENCHMARKS.md), [known limitations](LIMITATIONS.md), and
  [compatibility](COMPATIBILITY.md).

See also: [How it works](ARCHITECTURE.md) · [Benchmarks](BENCHMARKS.md) ·
[Known limitations](LIMITATIONS.md) · [Compatibility](COMPATIBILITY.md)
