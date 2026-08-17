package dev.kristian.cucumberfast

import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.plugins.cucumber.CucumberUtil
import org.jetbrains.plugins.cucumber.psi.GherkinStep

/**
 * End-to-end checks: a step in a feature file resolves through the Gherkin plugin's own reference
 * into the Java method, and the step definition method carries a gutter marker back.
 */
class StepResolutionTest : BasePlatformTestCase() {

    private val stepDefinitions = """
        import io.cucumber.java.en.Given;
        import io.cucumber.java.en.Then;

        public class CukeSteps {
            @Given("I have {int} cukes")
            public void iHaveCukes(int count) {}

            @Given("I have no cukes")
            public void iHaveNoCukes() {}

            @Then("^the belly is (.*)$")
            public void theBellyIs(String state) {}
        }
    """.trimIndent()

    fun testCucumberExpressionResolvesToItsMethod() {
        myFixture.addFileToProject("CukeSteps.java", stepDefinitions)
        myFixture.configureByText(
            "test.feature",
            """
            Feature: Cukes
              Scenario: Eating
                Given I have 42 cu<caret>kes
            """.trimIndent(),
        )

        assertEquals("iHaveCukes", resolvedMethodAtCaret()?.name)
    }

    /** The Ctrl+click / Ctrl+B path itself, not just the reference behind it. */
    fun testCtrlClickOnStepNavigatesToTheMethod() {
        myFixture.addFileToProject("CukeSteps.java", stepDefinitions)
        myFixture.configureByText(
            "test.feature",
            """
            Feature: Cukes
              Scenario: Eating
                Given I have 42 cu<caret>kes
            """.trimIndent(),
        )

        val target = GotoDeclarationAction.findTargetElement(project, myFixture.editor, myFixture.caretOffset)
        assertEquals("iHaveCukes", (target as? PsiMethod)?.name)
    }

    fun testRegexStepResolvesToItsMethod() {
        myFixture.addFileToProject("CukeSteps.java", stepDefinitions)
        myFixture.configureByText(
            "test.feature",
            """
            Feature: Cukes
              Scenario: Eating
                Then the belly is fu<caret>ll
            """.trimIndent(),
        )

        assertEquals("theBellyIs", resolvedMethodAtCaret()?.name)
    }

    fun testUndefinedStepResolvesToNothing() {
        myFixture.addFileToProject("CukeSteps.java", stepDefinitions)
        myFixture.configureByText(
            "test.feature",
            """
            Feature: Cukes
              Scenario: Eating
                Given I have a pineap<caret>ple
            """.trimIndent(),
        )

        assertNull(resolvedMethodAtCaret())
    }

    fun testStepDefinitionHasGutterMarkerToItsSteps() {
        myFixture.addFileToProject(
            "test.feature",
            """
            Feature: Cukes
              Scenario: Eating
                Given I have 42 cukes
                Given I have 7 cukes
            """.trimIndent(),
        )
        myFixture.configureByText("CukeSteps.java", stepDefinitions)

        val tooltips = myFixture.findAllGutters().mapNotNull { it.tooltipText }
        assertTrue("expected a gutter marker for the matched steps, got $tooltips", tooltips.any { it.contains("2 Gherkin steps") })
    }

    private fun resolvedMethodAtCaret(): PsiMethod? {
        val step = PsiTreeUtil.getParentOfType(
            myFixture.file.findElementAt(myFixture.caretOffset),
            GherkinStep::class.java,
        ) ?: error("no Gherkin step at the caret")
        val reference = CucumberUtil.getCucumberStepReference(step) ?: error("no step reference")
        return reference.resolve() as? PsiMethod
    }
}
