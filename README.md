> [!CAUTION]
> AI-Generated Codebase: This project was built entirely using artificial intelligence. Expect undocumented bugs, unexpected edge-case failures, and architectural quirks. Use at your own risk.

# Cucumber Fast

[![Build](https://github.com/kristianduke/cucumber-fast/actions/workflows/build.yml/badge.svg)](https://github.com/kristianduke/cucumber-fast/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

An IntelliJ IDEA plugin that links Gherkin feature files to Java step definitions and back — the
job "Cucumber for Java" does, with resolution built on a file index instead of a linear scan.

## What it does

| | |
| --- | --- |
| **Feature → Java** | Ctrl+B / Ctrl+click from a step to its definition. The Gherkin plugin's undefined-step inspection, step completion and rename resolve through the same path. |
| **Java → feature** | Gutter icon and a code vision hint (`3 Gherkin steps`) above each step definition, leading to the steps it implements. |
| **Unused step definitions** | Weak warning on a definition no feature file uses — dead code a test run never points out. |
| **Ambiguous steps** | Warning on a step two definitions match, which Cucumber fails at runtime and the IDE otherwise hides. |
| **Create step definition** | The quick fix on an undefined step writes the method, with `{int}`/`{string}`/`{float}` parameters derived from the step text. |

Recognised: annotation and `io.cucumber.java8` lambda step definitions, localized annotations
(`io.cucumber.java.de.Angenommen`) and localized feature files (`# language: de`), and project
`@ParameterType` declarations.

See [Not done yet](#not-done-yet) for what is missing — the biggest gap being that scenarios cannot
be *run* from the gutter.

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
| `index/JavaStepDefinitionScanner.kt` | Lexer-only scan of Java sources for step definitions and `@ParameterType` |
| `index/GherkinStepScanner.kt` | Line scan of `.feature` files, keywords per `# language:` |
| `index/JavaStepDefinitionIndex.kt` | Step definitions, bucketed by pattern prefix |
| `index/GherkinStepIndex.kt` | Feature steps, bucketed by leading words (reverse direction) |
| `index/ParameterTypeIndex.kt` | Project `@ParameterType` declarations by name |
| `steps/IndexedJavaStepDefinition.kt` | `AbstractStepDefinition` that resolves PSI only on a match |
| `steps/StepSearch.kt` | Index queries in both directions, plus the module-level cache |
| `steps/StepUsages.kt` | Which feature steps a definition implements, cached and shared |
| `steps/CucumberParameterTypes.kt` | Resolves `{colour}` against the project's declarations |
| `steps/StepSnippet.kt` | Step text → expression, method name and typed parameters |
| `steps/JavaCucumberExtension.kt` | The `cucumberJvmExtensionPoint` registration |
| `steps/JavaStepDefinitionCreator.kt` | Generates the step definition for the quick fix |
| `navigation/StepDefinitionLineMarkerProvider.kt` | Java → feature gutter marker |
| `navigation/StepUsagesCodeVisionProvider.kt` | The `N Gherkin steps` hint above a definition |
| `inspections/` | Unused step definition, ambiguous step |

### Where the parameter types fit

A project-defined `{colour}` changes what a pattern *matches* but not which bucket it belongs in, so
classification, literal prefix and index key are derived from the pattern text alone — the index has
no project context and could not resolve declarations anyway. Only the regex needs them, and only
for the patterns that name a custom type, so those are re-compiled on the query side and cached
until the declarations change.

Without this, such a pattern does not merely miss its bucket: IntelliJ leaves the unresolved
`{colour}` sitting in the regex, where it matches nothing at all.

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

## Installing it

The plugin is served from a custom plugin repository — no JetBrains Marketplace account, no plugin
signing, no review. GitHub releases host it.

### For everyone using the plugin, once

Settings | Plugins | ⚙ | **Manage Plugin Repositories…** | **+** →

```
https://github.com/kristianduke/cucumber-fast/releases/latest/download/updatePlugins.xml
```

"Cucumber Fast" then appears under the Marketplace tab and installs like any other plugin. That URL
is a permanent redirect to whichever release is newest, so updates arrive as the normal update
notification — the URL is never touched again.

IDEA fetches this URL anonymously, with no way to supply a token. That is why the repository is
public; a private one cannot be used as a plugin repository at all.

### Cutting a release

Tag it and push. `.github/workflows/release.yml` builds the plugin, generates the
`updatePlugins.xml` pointing at the new asset, and publishes both to a GitHub release:

```
# bump pluginVersion in gradle.properties first, then
git tag v0.2.0
git push origin v0.2.0
```

There is also a `workflow_dispatch` trigger taking the version as an input, if you would rather
release from the Actions tab than from a tag. Either way the version has to go up, or installed
IDEs will not offer the update.

`.github/workflows/build.yml` runs the tests on every push and pull request, and attaches the
plugin zip to the run so a branch build can be installed from disk without cutting a release.

### Hosting it somewhere else instead

The same two files work on any HTTPS host — an internal web server, S3, Artifactory. Point the
build at where they will live:

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
- **Scenarios cannot be run from the gutter.** The Gherkin plugin draws the run arrow beside
  `Scenario:` but registers no run configuration type or producer — those live in Cucumber for Java.
  Until this plugin supplies them, running a single scenario from the editor still needs that plugin
  installed. This is the largest remaining gap and the one that decides whether this is a
  replacement or only a navigation plugin.
- **Glue paths are not checked.** A step definition outside the glue configured for the run is
  linked here but never found at runtime. Worth doing once run configurations exist.
- **Localized lambda step definitions.** `io.cucumber.java8.En` lambdas are recognised by their
  English keywords; the localized interfaces (`io.cucumber.java8.De`) are not.
- **Wildcard-imported localized annotations.** `import io.cucumber.java.de.*` makes every annotation
  in the file a candidate, filtered later by package when a step matches. Correct, but it puts junk
  in the index.
- **Step text blocks.** A pattern written as a Java text block is skipped by the scanner.
- **Unknown parameter types match permissively.** A `{colour}` with no `@ParameterType` anywhere
  becomes `(.*)` so navigation still works. Cucumber would refuse the step outright, so the IDE is
  more forgiving here than the runtime.
- **Test coverage.** 57 tests: pattern logic, both scanners, snippet generation, and 14
  `BasePlatformTestCase` checks covering resolution, the Ctrl+click path, both inspections, custom
  parameter types, lambda and localized step definitions, and the generated step definition. The
  code vision hint is verified only by the shared usage count underneath it. Nothing asserts the
  performance claim — a benchmark over a synthetic suite of a few thousand step definitions is the
  obvious next test.

## License

MIT — see [LICENSE](LICENSE).
