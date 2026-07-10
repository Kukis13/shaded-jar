# FAST_SHADE — a fast Gradle plugin for fat & shaded JARs

## The idea

A Gradle plugin that builds fat JARs (uber JARs) and shaded JARs (with package
relocation) dramatically faster than the existing tooling, by parallelizing the
two CPU-heavy stages that today run single-threaded:

1. **Archive assembly** — merging tens of thousands of tiny `.class` files from
   dependency JARs into one archive, with parallel Deflate compression
   (directly reusing the engine/experience from the parallel-zip work in
   `ELEGANT_ZIP`).
2. **Relocation** — bytecode rewriting (ASM) of bundled dependencies into a
   shaded namespace, which is embarrassingly parallel per class file but is not
   parallelized by current plugins.

Plus **incrementality**: on rebuild, only re-compress/re-relocate entries whose
source dependency or class actually changed, instead of redoing the whole
archive.

## Why this niche is real (evidence gathered 2026-07-09)

- [gradle/gradle#2774](https://github.com/gradle/gradle/issues/2774) — "Build
  zips/jars in parallel", open since 2017, never implemented by Gradle itself.
- HubSpot's ["The Fault in Our JARs"](https://product.hubspot.com/blog/the-fault-in-our-jars-why-we-stopped-building-fat-jars):
  builds up to ~60% slower due to fat-JAR packaging; a 210 KB app became a
  158 MB / 101k-file fat JAR. They abandoned fat JARs rather than fix tooling.
- Every Spring Boot / Flink / Spark / Minecraft-plugin shop pays this cost on
  every build; the archive step is usually the slowest non-test task.

## Competition / prior art

- **Shadow plugin** (formerly johnrengelman/shadow, now GradleUp/shadow) — the
  de-facto standard for shading on Gradle. Community-rescued after
  abandonment; correctness-focused, not performance-focused. This is the
  benchmark to beat.
- **maven-shade-plugin** — Maven equivalent, notoriously slow on large
  dependency trees. A Maven port is a natural phase 2.
- **Spring Boot `repackage`** — nested-JAR style fat JARs; different format,
  same slow archive step.

## Differentiators

- Parallel compression + parallel ASM relocation (multi-core scaling).
- Incremental repackaging (only changed entries re-processed).
- Benchmark-driven marketing: a single "2 min → 20 s" chart against Shadow on
  real projects. Blog series doubles as promotion (see blog post stub
  "Slow shaded and fat JARs").
- Drop-in compatibility with Shadow's DSL where feasible, to make migration a
  one-line change.

## Status

- **Phase 1 (MVP): DONE** — the plugin lives at the repo root. A real
  Gradle plugin (`com.ljarocki.shaded-jar`) builds fat JARs by re-compressing every
  dependency in parallel on the Worker API, then assembling the parts
  single-threaded (first-wins dedup, generated manifest, signature stripping,
  reproducible timestamps, `@CacheableTask` incrementality). On an ~19.7k-entry /
  32 MiB sample it archives in **1871 ms vs Shadow's 3191 ms (~1.7×)** and stock
  Gradle Jar's 2637 ms, producing the smallest jar (measured on Gradle 9.6.1,
  Shadow 9.5.1, JDK 21). Reuses the parallel-Deflate + ZIP-writing engine from
  `ELEGANT_ZIP`. See [`README.md`](README.md).

## Design decisions

- **One repo, one plugin.** Shading is a *superset* of fat-jarring — the same
  enumerate → parallel-pack → assemble pipeline, plus one relocation stage. So a
  single plugin (`com.ljarocki.shaded-jar`) with a single task produces both.
  Matches Shadow and maven-shade, where "shade" is the umbrella term for
  uber-jar assembly with optional relocation. No second plugin, no second repo.
- **Fat vs shaded = presence of relocation rules, not a boolean.** A
  `shaded = true` flag can't express *what* to relocate and *where*; and
  "relocate everything" is a reflection/resources/service-file footgun. So: no
  `relocate(...)` rules → fat jar (Phase 1 behavior); one or more rules → shaded
  jar (Phase 2). This is the Shadow model.
- **Grow into a monorepo when Maven lands.** Split into `core/` (engine, no
  build-tool types) + `gradle-plugin/` + `maven-plugin/` at Phase 3 — one repo,
  multiple modules. Not before; today's single `plugin/` is fine.

## Scope / phases

1. **MVP**: fat JAR (no relocation) with parallel + incremental assembly;
   benchmark vs. Shadow and plain `jar`. **[done]**
2. **Shading**: parallel relocation with ASM; merge-strategy support
   (service files, duplicate handling) — correctness parity with Shadow's
   common cases.
3. **Maven plugin** sharing the same core engine.
4. **Longer term**: the same engine points at container image layers
   (tar+gzip) — the Jib-successor space
   ([Jib is in maintenance mode](https://github.com/GoogleContainerTools/jib)).

## Open questions

- Plugin name / coordinates — resolved: id `com.ljarocki.shaded-jar`, group
  `com.ljarocki`. Single plugin, both fat and shaded (see Design decisions).
- License — resolved: Apache-2.0 (`LICENSE` in repo).
- Minimum supported Gradle version — developed/tested on 9.6.1; plugin compiles
  to Java 8 bytecode and uses only stable Worker/Provider APIs, so it should
  support Gradle 8+. Confirm the floor with a matrix test before publishing.
- How much of Shadow's transformer API surface to support at Phase 2 (service
  files `META-INF/services/*` merging is table stakes; broader transformers TBD).
- Whether incremental Deflate (per-entry caching of compressed bytes) pays off
  vs. just parallelism — measure first.
