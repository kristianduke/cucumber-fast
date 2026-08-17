# Changelog

## [Unreleased]

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

[Unreleased]: https://github.com/kristianduke/cucumber-fast/compare/v0.4.0...HEAD
[0.4.0]: https://github.com/kristianduke/cucumber-fast/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/kristianduke/cucumber-fast/compare/v0.2.1...v0.3.0
[0.2.1]: https://github.com/kristianduke/cucumber-fast/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/kristianduke/cucumber-fast/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/kristianduke/cucumber-fast/releases/tag/v0.1.0
