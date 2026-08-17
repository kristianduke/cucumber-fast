package dev.kristian.cucumberfast

import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.plugins.cucumber.psi.GherkinStep

/**
 * Renaming a step has to change two things at once: the step text in every feature file that uses
 * it, and the pattern in the step definition that implements it. Changing only one silently breaks
 * the suite.
 */
class StepRenameTest : BasePlatformTestCase() {

    private fun stepDefinitions(): PsiFile = myFixture.addFileToProject(
        "CukeSteps.java",
        """
        import io.cucumber.java.en.Given;

        public class CukeSteps {
            @Given("I have {int} cukes")
            public void iHaveCukes(int count) {}
        }
        """.trimIndent(),
    )

    private fun stepAtCaret(): GherkinStep =
        PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(myFixture.caretOffset), GherkinStep::class.java)
            ?: error("no Gherkin step at the caret")

    fun testRenamingAStepUpdatesTheFeatureFileAndTheAnnotation() {
        val definitions = stepDefinitions()
        myFixture.configureByText(
            "test.feature",
            """
            Feature: Cukes
              Scenario: Eating
                Given I have 42 cu<caret>kes
                Then I am full
            """.trimIndent(),
        )

        // The rename dialog presents the pattern as a regex and locks its special symbols, so the
        // new name is the old regex with only its literal text reworded. Anchors are left off: the
        // processor re-attaches whichever the old pattern had.
        RenameProcessor(project, stepAtCaret(), """I have (-?\d+) pineapples""", false, false).run()

        assertTrue(
            "the feature file should use the new wording, got:\n${myFixture.file.text}",
            myFixture.file.text.contains("Given I have 42 pineapples"),
        )
        // A definition written as a Cucumber expression comes back as the equivalent regex: the
        // rename flow is regex-shaped, and turning `(-?\d+)` back into `{int}` would be a guess.
        assertTrue(
            "the step definition should carry the new pattern, got:\n${definitions.text}",
            definitions.text.contains("""@Given("^I have (-?\\d+) pineapples"""),
        )
        assertFalse(
            "the old wording should be gone, got:\n${definitions.text}",
            definitions.text.contains("cukes\""),
        )
    }

    fun testTheRenamedStepStillResolves() {
        stepDefinitions()
        myFixture.configureByText(
            "test.feature",
            """
            Feature: Cukes
              Scenario: Eating
                Given I have 42 cu<caret>kes
            """.trimIndent(),
        )

        // The rename dialog presents the pattern as a regex and locks its special symbols, so the
        // new name is the old regex with only its literal text reworded. Anchors are left off: the
        // processor re-attaches whichever the old pattern had.
        RenameProcessor(project, stepAtCaret(), """I have (-?\d+) pineapples""", false, false).run()

        val reference = org.jetbrains.plugins.cucumber.CucumberUtil.getCucumberStepReference(stepAtCaret())
        assertNotNull("the renamed step should still have a reference", reference)
        assertNotNull("the renamed step should still resolve to its definition", reference!!.resolve())
    }
}
