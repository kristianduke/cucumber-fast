package dev.kristian.cucumberfast

import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.kristian.cucumberfast.reference.FastCucumberStepReference
import dev.kristian.cucumberfast.inspections.UndefinedStepInspection
import org.jetbrains.plugins.cucumber.CucumberUtil
import org.jetbrains.plugins.cucumber.inspections.CucumberStepInspection
import org.jetbrains.plugins.cucumber.psi.GherkinStep

/**
 * The behaviour that moved off the Gherkin plugin's extension point and onto the indexed lookup:
 * undefined-step reporting, its quick fix, and step completion.
 */
class FastPathTest : BasePlatformTestCase() {

    private fun addStepDefinitions() = run {
        myFixture.addFileToProject(
            "CukeSteps.java",
            """
            import io.cucumber.java.en.Given;

            public class CukeSteps {
                @Given("I have {int} cukes")
                public void iHaveCukes(int count) {}
            }
            """.trimIndent(),
        )
    }

    fun testUndefinedStepIsReported() {
        addStepDefinitions()
        myFixture.enableInspections(UndefinedStepInspection())
        myFixture.configureByText(
            "test.feature",
            """
            Feature: Cukes
              Scenario: Eating
                Given I have 42 cukes
                Given I have a pineapple
            """.trimIndent(),
        )

        val undefined = myFixture.doHighlighting().mapNotNull { it.description }.filter { it.contains("Undefined step") }
        assertEquals("expected only the pineapple step to be undefined, got $undefined", 1, undefined.size)
    }

    /**
     * The Gherkin plugin's own inspection resolves through the extension point, which this plugin
     * leaves empty, so without suppression it would call every step undefined.
     */
    fun testTheGherkinPluginsOwnUndefinedStepInspectionIsSuppressed() {
        addStepDefinitions()
        myFixture.enableInspections(CucumberStepInspection())
        myFixture.configureByText(
            "test.feature",
            """
            Feature: Cukes
              Scenario: Eating
                Given I have 42 cukes
            """.trimIndent(),
        )

        val reported = myFixture.doHighlighting().mapNotNull { it.description }
        assertTrue("the superseded inspection should report nothing, got $reported", reported.isEmpty())
    }

    fun testBothInspectionsTogetherReportAStepOnlyOnce() {
        addStepDefinitions()
        myFixture.enableInspections(UndefinedStepInspection(), CucumberStepInspection())
        myFixture.configureByText(
            "test.feature",
            """
            Feature: Cukes
              Scenario: Eating
                Given I have a pineapple
            """.trimIndent(),
        )

        val undefined = myFixture.doHighlighting().mapNotNull { it.description }.filter { it.contains("ndefined") }
        assertEquals("expected exactly one report, got $undefined", 1, undefined.size)
    }

    fun testQuickFixWritesTheMissingStepDefinition() {
        addStepDefinitions()
        myFixture.enableInspections(UndefinedStepInspection())
        myFixture.configureByText(
            "test.feature",
            """
            Feature: Cukes
              Scenario: Eating
                Given I have 3 pineapples in my "big" basket
            """.trimIndent(),
        )

        val fix = myFixture.getAllQuickFixes().firstOrNull { it.familyName == "Create step definition" }
        assertNotNull("expected a create-step-definition fix", fix)
        myFixture.launchAction(fix!!)

        // Read through PSI: the fix edits the document, which is not written back to disk here.
        val stepDefinitions = com.intellij.psi.PsiManager.getInstance(project)
            .findFile(myFixture.findFileInTempDir("CukeSteps.java"))!!
        val generated = stepDefinitions.text
        assertTrue(
            "generated source was:\n$generated",
            generated.contains("""("I have {int} pineapples in my {string} basket")"""),
        )
        assertTrue(generated.contains("i_have_pineapples_in_my_basket(Integer int1, String string1)"))
    }

    /**
     * The Gherkin plugin's rename, its scenario-to-outline intention and its parameter highlighting
     * all take the *first* `CucumberStepReference` on a step and resolve through it. That has to be
     * this plugin's reference: the one the Gherkin plugin contributes no longer resolves, because
     * the extension point behind it deliberately returns nothing.
     */
    fun testTheGherkinPluginsOwnLookupFindsTheFastReference() {
        addStepDefinitions()
        myFixture.configureByText(
            "test.feature",
            """
            Feature: Cukes
              Scenario: Eating
                Given I have 42 cu<caret>kes
            """.trimIndent(),
        )
        val step = PsiTreeUtil.getParentOfType(
            myFixture.file.findElementAt(myFixture.caretOffset),
            GherkinStep::class.java,
        )!!

        val reference = CucumberUtil.getCucumberStepReference(step)
        assertNotNull("the Gherkin plugin must still find a step reference", reference)
        assertTrue(
            "it must find the indexed one, not the extension-point one",
            reference is FastCucumberStepReference,
        )
        assertEquals("iHaveCukes", (reference!!.resolveToDefinition()?.element as? PsiMethod)?.name)
    }

    fun testCompletionOffersStepDefinitions() {
        myFixture.addFileToProject(
            "CukeSteps.java",
            """
            import io.cucumber.java.en.Given;

            public class CukeSteps {
                @Given("I have {int} cukes")
                public void iHaveCukes(int count) {}

                @Given("I have no cukes at all")
                public void iHaveNone() {}

                @Given("somebody else has cukes")
                public void somebodyElse() {}
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "test.feature",
            """
            Feature: Cukes
              Scenario: Eating
                Given I have<caret>
            """.trimIndent(),
        )

        myFixture.completeBasic()
        val suggestions = myFixture.lookupElementStrings.orEmpty()
        assertTrue("expected the matching step definitions, got $suggestions", "I have {int} cukes" in suggestions)
        assertTrue("expected the matching step definitions, got $suggestions", "I have no cukes at all" in suggestions)
        // The prefix is the step text so far, not the word before the caret.
        assertFalse("a step starting differently should not be offered, got $suggestions", "somebody else has cukes" in suggestions)
    }
}
