# Known limitations

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
- **Resource transformers cover `META-INF/services/*`, the three Spring
  properties files** (`spring.factories`/`.handlers`/`.schemas`), **and
  log4j2's binary plugin cache** (`Log4j2Plugins.dat` — every source's cache
  is genuinely merged into one, via log4j-core's own `PluginCache`, not
  first-wins; relocated plugin class names are rewritten too). Other formats
  with their own merge semantics — Spring Boot's binary-adjacent config
  metadata, HOCON/`reference.conf` (Akka/Lightbend Config) — aren't handled
  yet; a dependency relying on one of those needs its merge/relocation
  handled another way for now.
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
  `exclude` (see [Usage](../README.md#usage) — a small pattern subset, not
  full Ant globs).
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

See also: [How it works](ARCHITECTURE.md) · [Benchmarks](BENCHMARKS.md) ·
[Compatibility](COMPATIBILITY.md) · [Development](DEVELOPMENT.md)
