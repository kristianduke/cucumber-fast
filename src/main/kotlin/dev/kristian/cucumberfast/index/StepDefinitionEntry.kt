package dev.kristian.cucumberfast.index

/** How a step definition was written, which decides how its PSI element is found again. */
enum class StepDefinitionKind {
    /** `@Given("…") public void method()` — the offset points at the `@`. */
    ANNOTATION,

    /** `Given("…", () -> {})` inside an `io.cucumber.java8` class — the offset points at the call. */
    LAMBDA,
}

/**
 * One step definition found in a Java file, recorded without resolving any PSI.
 *
 * [offset] is enough to find the owning method later — and only for the definitions that actually
 * matched a step.
 */
data class StepDefinitionEntry(
    val keyword: String,
    val expression: String,
    val offset: Int,
    val kind: StepDefinitionKind,
)

/** One `@ParameterType` declaration: the name a Cucumber expression refers to, and its regex. */
data class ParameterTypeEntry(
    val name: String,
    val regex: String,
    val offset: Int,
)

/** One step found in a `.feature` file. [offset] points at the first character of the step text. */
data class GherkinStepEntry(
    val text: String,
    val offset: Int,
)
