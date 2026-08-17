package dev.kristian.cucumberfast.index

/**
 * One step definition annotation found in a Java file, recorded without resolving any PSI.
 *
 * [annotationOffset] points at the `@` token, which is enough to find the owning method later —
 * and only for the definitions that actually matched a step.
 */
data class StepDefinitionEntry(
    val annotationName: String,
    val expression: String,
    val annotationOffset: Int,
)

/** One step found in a `.feature` file. [offset] points at the first character of the step text. */
data class GherkinStepEntry(
    val text: String,
    val offset: Int,
)
