package dev.kristian.cucumberfast.navigation

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.kristian.cucumberfast.steps.StepUsages

/** What the popup shows for each step, and what its model is allowed to contain. */
class StepUsagePopupTest : BasePlatformTestCase() {

    private fun items(): List<StepUsageItem> {
        myFixture.addFileToProject(
            "shopping.feature",
            """
            Feature: Cukes
              Scenario: Eating cukes
                Given I have 42 cukes
                Then I am full

              Scenario: Sharing cukes
                Given I have 42 cukes
                Then my friend is full
            """.trimIndent(),
        )
        val steps = myFixture.addFileToProject(
            "CukeSteps.java",
            """
            import io.cucumber.java.en.Given;

            public class CukeSteps {
                @Given("I have {int} cukes")
                public void iHaveCukes(int count) {}
            }
            """.trimIndent(),
        )
        val method = PsiTreeUtil.findChildrenOfType(steps, PsiMethod::class.java).single()
        return StepUsageItem.create(project, StepUsages.of(method))
    }

    fun testEachRowNamesItsFeatureAndScenario() {
        val items = items().sortedBy { it.container }
        assertEquals(2, items.size)

        assertEquals("Given I have 42 cukes", items[0].stepText)
        assertEquals("Cukes › Eating cukes", items[0].container)
        assertEquals("Cukes › Sharing cukes", items[1].container)
    }

    fun testEachRowNamesItsFileAndLine() {
        val items = items().sortedBy { it.container }
        // The step is on line 3 of the file, counting from 1.
        assertEquals("shopping.feature:3", items[0].location)
        assertEquals("shopping.feature:7", items[1].location)
    }

    fun testPreviewShowsTheWholeScenarioAndPointsAtTheStep() {
        val item = items().first { it.container.endsWith("Sharing cukes") }

        // Dedented as a block, so the scenario keeps its shape without hanging off to the right.
        assertEquals(
            listOf("Scenario: Sharing cukes", "  Given I have 42 cukes", "  Then my friend is full"),
            item.scenarioLines.map { it.trimEnd() },
        )
        assertEquals("  Given I have 42 cukes", item.scenarioLines[item.stepLineIndex].trimEnd())
    }

    /**
     * Regression: a popup model containing PSI elements makes `JBPopupFactory` log
     * "Do not use PsiElement for popup model", which surfaces as an assertion failure in the IDE.
     * The model therefore holds pointers.
     *
     * The platform skips that check when `isUnitTestMode()`, so calling the factory here would pass
     * whatever we handed it. The invariant itself is what this asserts.
     */
    fun testPopupModelHoldsNoPsiElements() {
        val items = items()
        assertFalse(items.isEmpty())
        assertFalse(items.any { it is PsiElement })
    }
}
