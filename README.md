> [!WARNING]
> This project was built utilising artificial intelligence. Expect possible undocumented bugs, unexpected edge-case failures, and architectural quirks.

# Cucumber Fast

[![Build](https://github.com/kristianduke/cucumber-fast/actions/workflows/build.yml/badge.svg)](https://github.com/kristianduke/cucumber-fast/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

An IntelliJ IDEA plugin that links Gherkin feature files to Java step definitions and back — the
job "Cucumber for Java" does, with resolution built on a file index instead of a linear scan.

## What it does

| | |
| --- | --- |
| **Feature → Java** | Ctrl+B / Ctrl+click from a step to its definition. The Gherkin plugin's undefined-step inspection, step completion and rename resolve through the same path. |
| **Java → feature** | Gutter icon and a code vision hint (`3 Gherkin steps`) above each step definition. Both open the same popup, where every row names the feature and scenario the step belongs to, and moving over a row previews that whole scenario with the step marked. |
| **Breakpoints in feature files** | Click the gutter beside a step. The debugger stops at the top of its step definition, before the step does anything — and only while *that* step is running, not for every other step sharing the definition. |
| **Unused step definitions** | Weak warning on a definition no feature file uses — dead code a test run never points out. |
| **Ambiguous steps** | Warning on a step two definitions match, which Cucumber fails at runtime and the IDE otherwise hides. |
| **Create step definition** | The quick fix on an undefined step writes the method, with `{int}`/`{string}`/`{float}` parameters derived from the step text. |

Recognised: annotation and `io.cucumber.java8` lambda step definitions, localized annotations
(`io.cucumber.java.de.Angenommen`) and localized feature files (`# language: de`), and project
`@ParameterType` declarations.

### Parameter types

All eleven of Cucumber's built-ins, each covered by a test that resolves a real step through it and
one that checks it rejects what it should:

| | |
| --- | --- |
| `{int}` `{byte}` `{short}` `{long}` `{biginteger}` | `-?\d+` — `42`, `-42`; not `forty` |
| `{float}` `{double}` `{bigdecimal}` | `-?\d*[.,]?\d+` — `1.5`, `-1.5`, `1,5` |
| `{word}` | a run without whitespace |
| `{string}` | a quoted value, single or double, escapes included |
| `{}` | anything |

Optional text (`cuke(s)`) and alternation (`cat/dog`) work too, as does any `@ParameterType` the
project declares. Custom types registered in code rather than by annotation — an older
`TypeRegistryConfigurer` — are not visible to the index and fall back to matching anything.

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

Resolution itself does *not* go through that extension point. Its contract is to hand back every
step definition in the module and let the caller filter them, and the Gherkin plugin runs that from
three places on every highlighting pass — its reference, its undefined-step inspection, and its
annotator's parameter highlighting. `JavaCucumberExtension` therefore returns nothing from
`loadStepsFor` and `getStepName`, so all three short-circuit. The extension stays registered for
what else hangs off it — notably `isGherkin6Supported`, which is what allows `Rule:` to parse.

`FastCucumberStepReference` takes over, and it *extends* the Gherkin plugin's own
`CucumberStepReference` rather than replacing it, registered at `HIGHER_PRIORITY` so it comes first
among a step's references. Several parts of the Gherkin plugin — renaming a step, the
scenario-to-outline intention, the annotator that colours step parameters — look up the first
`CucumberStepReference` on the element and resolve through it. Being one keeps all of that working
on the indexed lookup, instead of quietly breaking when the extension point stopped answering.

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

### What the benchmark says

`StepResolutionBenchmarkTest` builds a synthetic suite of 3,000 step definitions and times the
bucketed lookup against the linear one over 500 resolutions:

| Suite | Linear | Bucketed | |
| --- | --- | --- | --- |
| Steps starting many different ways | ~39ms | ~2ms | **~14x faster** |
| Every step sharing its first two words | ~36ms | ~36ms | no better, no worse |

The second row is the point of the second test: when bucketing cannot narrow anything it degrades to
the linear cost rather than falling off a cliff.

This comparison is conservative. The "linear" side is *this plugin's* linear pass, where each
definition answers from indexed data with a literal-prefix pre-check; IntelliJ's own pass parses PSI
and runs a regex per definition, and rebuilds the whole set whenever any file changes.

The benchmark earned its place immediately: the first version of the bucketed lookup queried the
index on every call and rebuilt a step definition — and a smart pointer — per candidate, which
measured **7x slower** than the linear pass it was meant to beat. It now filters the cached
module list instead.

### Pattern classification

Cucumber JVM treats an annotation value as a regular expression when it is anchored (`^…$`) and as a
Cucumber expression otherwise. IntelliJ instead requires a `{placeholder}` before it calls something
an expression, so a plain-text pattern (`there are no cukes left`) is treated as an unanchored regex
— matching more loosely than the runtime does. `StepPattern` follows the runtime's rule, widened
only for patterns carrying regex syntax a Cucumber expression could not contain (`\`, `[`, `.*`,
`.+`), so hand-written unanchored regexes keep working.

### Breakpoints on steps

A `.feature` line has no bytecode, so a breakpoint on one cannot be a breakpoint on that line. It is
translated instead: the step is resolved to its step definition — the same indexed lookup Ctrl+B
uses — and the JDI request goes on the first executable line of that method. The pause therefore
lands inside the Java step definition, at the top, before the step does its work. JetBrains has had
this open as [IDEA-98387](https://youtrack.jetbrains.com/issue/IDEA-98387) since 2012; Cucumber for
Java does not provide it.

Three pieces, all standard extension points:

- `GherkinStepBreakpointType` extends the platform's `JavaLineBreakpointTypeBase`, which is what
  makes the JVM debugger willing to carry a breakpoint owned by another language. Java's condition
  editor, filters panel and suspend policies come with it. It offers itself only on a line that
  *starts* a step, so a table row or doc string under one is not breakpointable.
- `GherkinStepBreakpointHandlerFactory` registers at `debugger.javaBreakpointHandlerFactory`, the
  extension point every Java debug process asks for extra breakpoint handlers. That is why these
  breakpoints need no run configuration from this plugin and work under any JVM debug session —
  JUnit, Gradle, Maven or a remote attach.
- `GherkinStepBreakpoint` extends `LineBreakpoint` and answers `getSourcePosition()` with the Java
  position rather than the feature line the gutter icon sits on. Everything downstream — the
  class-prepare request, the search for locations, lambda bodies, conditions — is the platform's
  own, unchanged.

Two things are added on top of that. Locations are accepted only in the method actually holding the
definition: a one-line lambda step definition shares its line with the call that registers it, so
without the filter the breakpoint would also stop while the glue is being loaded. And the breakpoint
checks *which* step is running before it suspends — a step definition is normally shared, and the
JVM breakpoint alone would stop on every step using it.

That check reads the frame that invokes the definition, `PickleStepDefinitionMatch`, which holds the
feature file's URI and the step. The URI is matched by suffix, since Cucumber reports
`classpath:features/eat.feature` or an absolute `file:` URI depending on how the run was pointed at
the features. It is deliberately coupled to Cucumber's internals and deliberately fails *open*: a
Cucumber arranged differently enough that the frame is not recognised leaves the breakpoint behaving
like an ordinary one on the step definition, which is the behaviour it replaces.

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
| `reference/FastCucumberStepReference.kt` | Feature → Java resolution, extending the Gherkin plugin's reference |
| `completion/StepCompletionContributor.kt` | Step completion from the indexed definitions |
| `navigation/StepDefinitionLineMarkerProvider.kt` | Java → feature gutter marker |
| `navigation/StepUsagesCodeVisionProvider.kt` | The `N Gherkin steps` hint above a definition |
| `inspections/` | Unused step definition, ambiguous step |
| `debugger/GherkinStepBreakpointType.kt` | The breakpoint type offered on feature-file steps |
| `debugger/GherkinStepBreakpoint.kt` | The Java breakpoint it becomes, installed on the step definition |
| `debugger/GherkinStepTarget.kt` | Step → the Java line the request goes on, and how to identify the step at runtime |
| `debugger/RunningStep.kt` | Reads which step Cucumber is running out of the debuggee's stack |

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

- **Scenarios cannot be run from the gutter.** The Gherkin plugin draws the run arrow beside
  `Scenario:` but registers no run configuration type or producer — those live in Cucumber for Java.
  Until this plugin supplies them, running a single scenario from the editor still needs that plugin
  installed. This is the largest remaining gap and the one that decides whether this is a
  replacement or only a navigation plugin.
- **Breakpoints stop in Java, not in the feature file.** A breakpoint on a step suspends at the top
  of its step definition, and stepping from there moves through Java. There is no position mapping
  back the other way, so the feature file is not what the debugger highlights while suspended, and
  Step Over does not walk from one step to the next. Breakpoints go on steps only — not on
  `Scenario:`, `Background:` or hook lines.
- **Which step is running is read out of Cucumber's internals.** The check that stops a breakpoint
  from firing for every other step sharing the definition reads the `PickleStepDefinitionMatch`
  frame. Its matching rule is tested; the JDI half of it is not, because that needs a live debug
  session against a real Cucumber run. A Cucumber that does not present that frame — an old enough
  4.x, or a wrapper that hides it — falls back to stopping for every step using the definition.
- **A breakpoint on an ambiguous step takes the first definition.** Cucumber fails such a step at
  runtime anyway, and the *Ambiguous Cucumber step* inspection already reports it.
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
- **Renaming a step rewrites the pattern as a regex.** The rename dialog shows the step definition's
  pattern as a regular expression and locks its special symbols, so a definition written as a
  Cucumber expression comes back as the equivalent regex. Cucumber for Java behaves the same way.
  Rename also does not warn about a collision with another definition; the *Ambiguous Cucumber step*
  inspection reports it straight afterwards.
- **Rename skips Scenario Outline steps.** Values are recovered by matching each usage against the
  old pattern, and `<count>` never matches `(-?\d+)`, so those steps keep their old wording while the
  step definition and every ordinary step are rewritten — leaving the definition matching only some
  of its usages. Ordinary parameters are fine: `{int}` values keep their order and `{string}` keeps
  its quotes. Fixing the outline case means taking over the rename processor and matching against the
  substituted step text.
- **Step completion is a flat list.** Every definition in the module is offered, filtered by the
  step text typed so far. The Gherkin plugin's own completion additionally understood table rows and
  inserted parameter placeholders; that is not reproduced yet.
- **Updates may not be offered promptly.** The IDE caches custom repository listings
  (`RepositoryHelper`), so a new release can go unnoticed until Settings | Plugins | ⚙ |
  *Check for Updates*. Serving `updatePlugins.xml` from a stable path instead of the
  `releases/latest/download` redirect would remove one suspected cause, at the cost of everyone
  re-entering the URL.
- **Test coverage.** 90 tests: pattern logic, both scanners, snippet generation, a benchmark, and
  `BasePlatformTestCase` checks covering resolution, the Ctrl+click path, all three inspections,
  suppression of the superseded one, completion, the generated step definition, custom parameter
  types, lambda and localized step definitions, and where a feature-file breakpoint installs itself.
  The code vision hint and the parameter annotator are verified only by the lookups underneath them,
  not by their rendering, and the debugger tests stop at the point a live JVM would be needed.

## License

MIT — see [LICENSE](LICENSE).
