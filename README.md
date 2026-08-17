# Cucumber Fast

An IntelliJ IDEA plugin that links Gherkin feature files to Java step definitions and back — the
job "Cucumber for Java" does, with resolution built on a file index instead of a linear scan.

## Status

Working vertical slice: feature → Java resolution (Ctrl+B, undefined-step inspection, completion,
rename all go through it) and a gutter marker from a step definition method to the feature steps it
implements. See [Not done yet](#not-done-yet).

## How it is put together

The Gherkin language itself — lexer, parser, PSI, highlighting, formatter — comes from JetBrains'
[Gherkin plugin](https://plugins.jetbrains.com/plugin/9164-gherkin), declared as a build and runtime
dependency. This plugin supplies only what that plugin delegates through its
`org.jetbrains.plugins.cucumber.steps.cucumberJvmExtensionPoint` extension point: finding the Java
step definitions, and deciding which one a step resolves to.

Registering at that extension point rather than contributing a separate reference is deliberate.
Everything the Gherkin plugin builds on step resolution — `CucumberStepReference`, the
undefined-step inspection, step completion, rename, "go to related" — routes through it, so all of
them pick up the indexed implementation at once. A separate reference would resolve steps *and*
leave the Gherkin plugin's own inspection flagging every one of them as undefined.

### What makes it faster

JetBrains' implementation (`CucumberStepHelper.findStepDefinitions`) builds *every* step definition
in the module — walking the PSI of every step definition file, resolving every annotation — and then
runs a regex against each one. The result is cached against `PsiModificationTracker.MODIFICATION_COUNT`,
which any keystroke anywhere invalidates. In a large suite that is a full rebuild per keypress.

Three changes:

1. **Indexed discovery.** `JavaStepDefinitionIndex` records step definitions found by a Java *lexer*
   pass — no parse, no PSI, no resolve. `IndexedJavaStepDefinition` answers `matches()` from the
   indexed pattern alone, so a definition that does not match a step never has its file parsed.
   PSI is built only for definitions that matched, in `getElement()`, which is also where the
   annotation is confirmed to really be Cucumber's.

2. **Bucketing.** Every pattern is keyed on the first one or two literal words it must match
   (`I have {int} cukes` → `i have`). A step queries its own two keys plus the catch-all bucket
   holding the patterns that start with a placeholder, so the number of candidates does not grow
   with the size of the suite. Patterns that cannot be bucketed soundly — unanchored regexes, which
   IntelliJ matches with `find()` and may match mid-step — stay in the catch-all bucket and keep the
   linear path.

3. **Caching against the index, not against PSI.** The module-wide definition list is cached against
   the index's modification stamp, so editing a feature file — or any file with no step definitions
   in it — reuses it instead of rebuilding it.

The literal prefix does the same work twice over: it rejects a non-matching step with a
`String.startsWith` before the regex engine is involved, and it is what the index key is derived
from.

### Pattern classification

Cucumber JVM treats an annotation value as a regular expression when it is anchored (`^…$`) and as a
Cucumber expression otherwise. IntelliJ instead requires a `{placeholder}` before it calls something
an expression, so a plain-text pattern (`there are no cukes left`) is treated as an unanchored regex
— matching more loosely than the runtime does. `StepPattern` follows the runtime's rule, widened
only for patterns carrying regex syntax a Cucumber expression could not contain (`\`, `[`, `.*`,
`.+`), so hand-written unanchored regexes keep working.

### Source map

| Path | What it does |
| --- | --- |
| `expression/StepPattern.kt` | Pattern classification, regex compilation, literal prefix, index keys |
| `expression/StandardParameterTypes.kt` | Built-in `{int}`, `{word}`, … parameter types |
| `index/JavaStepDefinitionScanner.kt` | Lexer-only scan of Java sources for step annotations |
| `index/GherkinStepScanner.kt` | Line scan of `.feature` files for steps |
| `index/JavaStepDefinitionIndex.kt` | Step definitions, bucketed by pattern prefix |
| `index/GherkinStepIndex.kt` | Feature steps, bucketed by leading words (reverse direction) |
| `steps/IndexedJavaStepDefinition.kt` | `AbstractStepDefinition` that resolves PSI only on a match |
| `steps/StepSearch.kt` | Index queries in both directions, plus the module-level cache |
| `steps/JavaCucumberExtension.kt` | The `cucumberJvmExtensionPoint` registration |
| `navigation/StepDefinitionLineMarkerProvider.kt` | Java → feature gutter marker |

## Building

```
./gradlew build        # compile + tests
./gradlew runIde       # sandbox IDE with the plugin installed
./gradlew buildPlugin  # build/distributions/cucumber-fast-<version>.zip
```

Target platform is IntelliJ IDEA Community 2025.1.2, matching the locally installed 2025.1.2
(build 251.26094.121). Versions live in `gradle/libs.versions.toml`.

The Gherkin plugin declares `until-build="251.*"`, so a newer IDE needs a matching Gherkin build in
`libs.versions.toml`; find compatible versions with:

```
curl -X POST https://plugins.jetbrains.com/api/search/compatibleUpdates \
  -H 'Content-Type: application/json' \
  -d '{"build":"IU-251.26094.121","pluginXMLIds":["gherkin"]}'
```

Code instrumentation is off in the build (`instrumentCode = false`): there is no Java source and no
GUI forms, and the ant-based instrumenter fails against the Microsoft JDK on this machine.

## Distributing inside an organisation

The plugin is served from a custom plugin repository, which is just two files on any HTTPS host —
an internal web server, an S3 bucket, Artifactory, GitHub Pages. No JetBrains Marketplace account,
no plugin signing, no review.

### Publishing a version

```
./gradlew buildPlugin -PpluginRepositoryUrl=https://your-host/idea-plugins
```

That writes both artifacts to `build/distributions/`:

| File | Purpose |
| --- | --- |
| `cucumber-fast-<version>.zip` | the plugin |
| `updatePlugins.xml` | the repository index, pointing at the zip's URL |

Upload both to `https://your-host/idea-plugins/`. The zip must end up at exactly the `url` in the
XML — check it if the host rewrites paths. Forgetting `-PpluginRepositoryUrl` is not silent: the URL
is written against a `.invalid` host and the build logs a warning.

Hosts that do not serve the archive from a predictable path — a GitHub release asset, a presigned S3
link — can set the download URL outright instead:

```
./gradlew buildPlugin -PpluginDownloadUrl=https://github.com/<org>/<repo>/releases/download/v0.1.0/cucumber-fast-0.1.0.zip
```

### What each developer does, once

Settings | Plugins | ⚙ | **Manage Plugin Repositories…** | **+** →
`https://your-host/idea-plugins/updatePlugins.xml`

"Cucumber Fast" then appears under the Marketplace tab and installs like any other plugin. Updates
arrive as the normal update notification.

### Shipping an update

Bump `pluginVersion` in `gradle.properties`, rebuild, upload both files again. IDEs compare the
`version` in `updatePlugins.xml` against what is installed, so the version has to go up for anyone
to be offered the update. Keeping the old zips around costs nothing and gives you a rollback — the
XML only ever points at the current one.

### The Gherkin dependency

Every machine needs JetBrains' Gherkin plugin, which IDEA offers to fetch automatically when
installing this one. On machines that cannot reach the JetBrains Marketplace, that fetch fails and
the plugin will not enable. Mirroring Gherkin into the same internal repository works technically —
add a second `<plugin>` entry pointing at its zip — but check JetBrains' redistribution terms before
doing it.

## Not done yet

- **Feature → Java is not yet on the fast path.** The extension point contract hands back *all*
  definitions and lets the Gherkin plugin filter them, so the bucketed lookup
  (`StepSearch.definitionsForStep`) is written and tested but only the gutter marker uses it. The
  per-definition work is already index-backed and PSI-free, so the linear pass is cheap; making it
  sublinear needs a reference contributor of our own, which in turn needs the Gherkin plugin's
  undefined-step inspection to be suppressed rather than fought.
- **Localized keywords.** Only English Gherkin keywords and English (`io.cucumber.java.en`)
  annotations are recognised. A localized feature file contributes nothing to the reverse index.
- **Custom parameter types.** `@ParameterType` declarations are not resolved; an expression using
  one falls back to the slow path instead of matching incorrectly.
- **Java 8 lambda step definitions** (`Given("…", () -> {})`) are not indexed — only annotations.
- **Step text blocks.** A pattern written as a Java text block is skipped by the scanner.
- **"Create step definition"** creates the container class but does not yet generate the method.
- **Test coverage.** 31 tests: the pattern logic, both scanners, and five `BasePlatformTestCase`
  checks covering resolution end to end, including the Ctrl+click path itself. Nothing yet asserts the performance claim — a benchmark
  over a synthetic suite of a few thousand step definitions is the obvious next test.
