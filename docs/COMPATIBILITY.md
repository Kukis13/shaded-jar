# Compatibility

- **Gradle 8 and 9** — [`GradleVersionCompatibilityFunctionalTest`](../plugin/src/test/java/com/ljarocki/shadedjar/GradleVersionCompatibilityFunctionalTest.java)
  runs a real fat/shaded build against Gradle 8.5 and 9.6.1 explicitly (via
  TestKit's `withGradleVersion`, independent of whatever version the wrapper
  pins). 8.5, not 8.0, is the actual floor tested: it's the earliest Gradle
  8.x release that runs on a JDK 21 daemon, which is what this project's CI
  uses. The plugin's own bytecode targets Java 8 and only uses Provider/Worker
  APIs stable since well before Gradle 8, so nothing here suggests true
  8.0–8.4 would behave differently — it's just genuinely untested, rather
  than "known to work."
- **Configuration cache** — [`ConfigurationCacheFunctionalTest`](../plugin/src/test/java/com/ljarocki/shadedjar/ConfigurationCacheFunctionalTest.java)
  runs `fatJar --configuration-cache` twice per Gradle version above: once to
  store the cache, once more (no changes) to confirm it's actually *reused*,
  not just that the first run didn't complain. Both major versions pass with
  no reported problems.

See also: [How it works](ARCHITECTURE.md) · [Benchmarks](BENCHMARKS.md) ·
[Known limitations](LIMITATIONS.md) · [Development](DEVELOPMENT.md)
