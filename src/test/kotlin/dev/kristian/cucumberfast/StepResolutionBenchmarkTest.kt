package dev.kristian.cucumberfast

import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.kristian.cucumberfast.steps.IndexedJavaStepDefinition
import dev.kristian.cucumberfast.steps.StepSearch

/**
 * Measures the claim the plugin rests on: that resolving a step costs about the same whether the
 * project has a hundred step definitions or a few thousand.
 *
 * Two lookups are compared over the same synthetic suite:
 *
 *  - the **bucketed** one, [StepSearch.definitionsForStep], which only considers definitions whose
 *    pattern could start the way the step does;
 *  - the **linear** one, which is what IntelliJ's own resolution does: ask every step definition in
 *    the module whether it matches.
 *
 * The linear pass here is already cheaper than IntelliJ's, because these definitions answer
 * `matches()` from indexed data with a literal-prefix pre-check rather than parsing PSI and running
 * a regex. That makes this a conservative comparison: the real gap against stock IntelliJ is wider.
 *
 * Timings are noisy on CI, so the assertions are loose and describe the shape of the curve rather
 * than a wall-clock budget. The numbers themselves are printed.
 */
class StepResolutionBenchmarkTest : BasePlatformTestCase() {

    private companion object {
        /** Step definitions to generate: enough to separate the two curves, quick enough to index. */
        const val DEFINITION_COUNT = 3_000

        /** Lookups per measurement. */
        const val ITERATIONS = 500

        const val PER_FILE = 500

        /**
         * Distinct openings, so definitions spread across buckets the way a real suite does. Real
         * suites are less even than this — many steps start "I " — which the shared-prefix test
         * covers as the opposite extreme.
         */
        val OPENINGS = listOf(
            "the user", "an admin", "the system", "a customer", "the operator", "a guest",
            "the service", "a report", "the account", "an order", "the invoice", "a session",
        )
    }

    /** [openings] cycles through the given prefixes; a single-element list puts everything in one bucket. */
    private fun generateStepDefinitions(openings: List<String>) {
        for (fileIndex in 0 until (DEFINITION_COUNT + PER_FILE - 1) / PER_FILE) {
            val methods = StringBuilder()
            for (i in fileIndex * PER_FILE until minOf((fileIndex + 1) * PER_FILE, DEFINITION_COUNT)) {
                methods.append("    @Given(\"${openings[i % openings.size]} has widget $i in state {word}\")\n")
                methods.append("    public void widget$i(String state) {}\n\n")
            }
            myFixture.addFileToProject(
                "steps/Steps$fileIndex.java",
                "package steps;\nimport io.cucumber.java.en.Given;\n\npublic class Steps$fileIndex {\n$methods}\n",
            )
        }
        myFixture.configureByText("bench.feature", "Feature: F\n  Scenario: S\n    Given a step\n")
    }

    private fun module(): Module = ModuleUtilCore.findModuleForPsiElement(myFixture.file)!!

    fun testBucketedLookupIsFasterWhenStepsStartDifferently() {
        generateStepDefinitions(OPENINGS)
        val (linear, bucketed) = compare { i -> "${OPENINGS[i % OPENINGS.size]} has widget $i in state ready" }

        assertTrue(
            "bucketed lookup should clearly beat scanning all $DEFINITION_COUNT definitions, " +
                "but took ${linear.describe(bucketed)}",
            bucketed * 2 < linear,
        )
    }

    /**
     * The opposite extreme: every definition starts with the same two words, so bucketing cannot
     * narrow anything and both paths consider all $DEFINITION_COUNT definitions. The point is that
     * bucketing degrades to the linear cost rather than falling off a cliff.
     */
    fun testBucketedLookupDoesNotRegressWhenEveryStepSharesAPrefix() {
        generateStepDefinitions(listOf("the system"))
        val (linear, bucketed) = compare { i -> "the system has widget $i in state ready" }

        assertTrue(
            "bucketing should degrade to roughly the linear cost, but took ${linear.describe(bucketed)}",
            bucketed < linear * 3,
        )
    }

    fun testMatchingStepsAreFoundEitherWay() {
        generateStepDefinitions(OPENINGS)
        val module = module()
        val stepText = "${OPENINGS[7 % OPENINGS.size]} has widget 7 in state ready"

        val definitions = StepSearch.allDefinitions(module)
        assertTrue("expected the suite to be indexed, got ${definitions.size}", definitions.size >= DEFINITION_COUNT)

        assertEquals(linearLookup(definitions, stepText), StepSearch.definitionsForStep(module, stepText))
        assertEquals(1, StepSearch.definitionsForStep(module, stepText).size)
        assertTrue(StepSearch.definitionsForStep(module, "nothing whatsoever matches this").isEmpty())
    }

    /** Runs both lookups over the same step texts and returns (linear, bucketed) nanoseconds. */
    private fun compare(stepText: (Int) -> String): Pair<Long, Long> {
        val module = module()
        val definitions = StepSearch.allDefinitions(module)
        val texts = (0 until ITERATIONS).map(stepText)

        repeat(50) {
            StepSearch.definitionsForStep(module, texts[it % texts.size])
            linearLookup(definitions, texts[it % texts.size])
        }

        val linear = measure { texts.forEach { linearLookup(definitions, it) } }
        val bucketed = measure { texts.forEach { StepSearch.definitionsForStep(module, it) } }

        println(
            "$DEFINITION_COUNT definitions, $ITERATIONS lookups: " +
                "linear ${linear / 1_000_000}ms, bucketed ${bucketed / 1_000_000}ms " +
                "(${"%.1f".format(linear.toDouble() / bucketed.coerceAtLeast(1))}x)",
        )
        return linear to bucketed
    }

    private fun Long.describe(bucketed: Long) =
        "${bucketed / 1_000_000}ms bucketed against ${this / 1_000_000}ms linear"

    /** What IntelliJ's own resolution does: ask every definition in the module. */
    private fun linearLookup(definitions: List<IndexedJavaStepDefinition>, stepText: String) =
        definitions.filter { it.matches(stepText) }

    private fun measure(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return System.nanoTime() - start
    }
}
