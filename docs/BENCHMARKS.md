# Benchmarks

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

shaded-jar is **~3× faster than com.gradleup.shadow** with relocation on, and produces the
smallest jar. Both `fatJar` and `shadowJar` relocate Guava and merge service
files; relocation is nearly free for shaded-jar because it runs inside the
per-source parallel workers, whereas it almost doubled Shadow's time. Measured on
Gradle 9.6.1, Shadow 9.5.1, JDK 21. (This table is always `--rerun-tasks` with
the pack cache cleared first, so it reflects a genuinely from-scratch archive
step — see "Pack cache: cold vs warm rebuild" below for what a warm cache does
on top of this.)

\* Stock Gradle `Jar` cannot relocate, so it's shown only as a fat-jar floor, not
a like-for-like shading comparison. Reproduce all of this with `bash benchmark.sh`.

## Single worker: algorithm vs parallelism

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
  file header, skipping the inflate/re-deflate round trip entirely (see
  [How it works](ARCHITECTURE.md)); the per-source part-file intermediate is
  the only remaining overhead, so the lead over stock is no longer *purely*
  parallelism.

## Pack cache: cold vs warm rebuild

The numbers above are all `--rerun-tasks` with the pack cache cleared first —
a genuinely from-scratch archive step, same as before the cache existed. The
cache's own effect only shows up across *separate* builds where the
dependencies haven't changed (see [How it works](ARCHITECTURE.md)), so it's
measured separately: same shaded sample, cache cleared once, then two
consecutive `fatJar` runs.

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

## Reproducing these numbers

```
bash benchmark.sh            # local timing harness (uses `gradle`; GRADLE=./gradlew to pin)
```

CI ([`.github/workflows/ci.yml`](../.github/workflows/ci.yml)) runs it on every
push to `main` (results land in the run's job summary).

See also: [How it works](ARCHITECTURE.md) · [Known limitations](LIMITATIONS.md) ·
[Compatibility](COMPATIBILITY.md) · [Development](DEVELOPMENT.md)
