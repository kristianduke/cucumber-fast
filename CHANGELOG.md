# Changelog

## [Unreleased]

## [0.4.2]

### Fixed

- Renaming a step left the step definition's pattern untouched, silently undefining every step it
  had just reworded. The definition now rewrites its own annotation, as Cucumber for Java does.
- `getCucumberRegex` returned the pattern as written rather than as a regular expression, which is
  what the contract means. For a Cucumber expression that made `{int}` read as a quantifier: renaming
  a step threw `PatternSyntaxException`, and step parameter highlighting silently produced nothing.

### Known behaviour

- Renaming a step definition written as a Cucumber expression converts it to the equivalent regex
  (`I have {int} cukes` becomes `^I have (-?\d+) cukes$`). The rename flow is regex-shaped — the
  dialog shows the pattern as a regex and locks its special symbols — and this is what Cucumber for
  Java does as well.
- Renaming does not warn when the new wording collides with another step definition. The
  *Ambiguous Cucumber step* inspection reports it immediately afterwards.

## [0.4.1]

### Fixed

- Renaming a step, the scenario-to-outline intention and step parameter highlighting stopped working
  in 0.4.0. All three take the first `CucumberStepReference` on a step and resolve through it, and
  moving resolution off the extension point left that one answering nothing. This plugin's reference
  now *is* one, registered ahead of it.
- A step definition whose pattern is a constant rather than a string literal — `@Given(STEP_TEXT)` —
  was reported as unused. Its pattern cannot be read, so nothing can be said about it.

### Changed

- Looking up which feature steps a definition implements now reads a cached bucket map. It queried
  the index once per bucket per call, and for a pattern starting with a placeholder, once per key in
  the whole index — on every highlighting pass, for every step definition on screen.
- Deciding whether a method is a step definition resolves its annotations once per method and shares
  the answer with the usage count, rather than resolving every annotation on every method twice per
  pass.

## [0.4.0]

### Changed

- Step resolution no longer goes through the Gherkin plugin's extension point, which asks every step
  definition in the module about every step — from its reference, its undefined-step inspection and
  its parameter highlighting, on every highlighting pass. Steps now resolve through the bucketed
  index. Over a synthetic suite of 3,000 definitions this is roughly 14x faster, and no slower when
  every step shares its opening words.
- The step popup puts the file and line on their own third line, under the scenario name.

### Added

- Undefined-step inspection, step completion and step parameter highlighting, replacing the Gherkin
  plugin's versions of each now that resolution has moved. Its undefined-step inspection is
  suppressed so a step is never reported twice.
- A benchmark comparing the bucketed lookup against the linear one over 3,000 step definitions.

### Fixed

- The bucketed lookup rebuilt every candidate step definition, and a smart pointer with it, on each
  call, which made it several times slower than the linear pass it replaces. It now filters the
  cached module list.

## [0.3.0]

### Added

- Update notes: releases now carry a changelog, shown both in the update prompt before installing and
  under **What's New** in Settings | Plugins afterwards.

### Changed

- The step popup drops the feature name from each row and puts the file and line directly beneath the
  step, so rows stay narrow and scannable.

## [0.2.1]

### Fixed

- Clicking the code vision hint failed with "Do not use PsiElement for popup model". The popup model
  no longer holds PSI elements.

### Changed

- Each row of the step popup names the scenario it belongs to and its file and line, and moving over
  a row previews the whole scenario with the step marked. The gutter icon opens this same popup, so
  both entry points match.

## [0.2.0]

### Added

- Inspection for step definitions no feature file uses.
- Inspection for steps matched by more than one definition, which Cucumber fails at runtime with
  `AmbiguousStepDefinitionsException` and the IDE otherwise hides.
- Code vision hint above each step definition showing how many steps it implements.
- The "create step definition" quick fix now writes the method, deriving `{int}`, `{string}` and
  `{float}` parameters from the step text.
- Project `@ParameterType` declarations are resolved. Previously an unresolved `{colour}` stayed in
  the regex and matched nothing.
- Localized step annotations (`io.cucumber.java.de.Angenommen`), recognised through their import.
- Lambda step definitions in `io.cucumber.java8` classes.
- Localized feature files, using the keywords of their `# language:` header.

## [0.1.0]

### Added

- Navigation between Gherkin steps and Java step definitions in both directions, resolved through a
  file index bucketed by each pattern's literal prefix rather than by scanning every definition.

[Unreleased]: https://github.com/kristianduke/cucumber-fast/compare/v0.4.2...HEAD
[0.4.2]: https://github.com/kristianduke/cucumber-fast/compare/v0.4.1...v0.4.2
[0.4.1]: https://github.com/kristianduke/cucumber-fast/compare/v0.4.0...v0.4.1
[0.4.0]: https://github.com/kristianduke/cucumber-fast/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/kristianduke/cucumber-fast/compare/v0.2.1...v0.3.0
[0.2.1]: https://github.com/kristianduke/cucumber-fast/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/kristianduke/cucumber-fast/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/kristianduke/cucumber-fast/releases/tag/v0.1.0
