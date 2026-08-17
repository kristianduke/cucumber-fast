package dev.kristian.cucumberfast

import com.intellij.psi.PsiFile
import dev.kristian.cucumberfast.inspections.UndefinedStepInspection
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

    /** The values captured by `{int}` must survive; only the words around them change. */
    fun testRenameKeepsEveryParameterValue() {
        val definitions = myFixture.addFileToProject(
            "CukeSteps.java",
            """
            import io.cucumber.java.en.Given;

            public class CukeSteps {
                @Given("I move {int} cukes from bin {int}")
                public void iMove(int count, int bin) {}
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "test.feature",
            """
            Feature: Cukes
              Scenario: Moving
                Given I move 42 cu<caret>kes from bin 7
            """.trimIndent(),
        )

        RenameProcessor(project, stepAtCaret(), """I relocate (-?\d+) cukes from crate (-?\d+)""", false, false).run()

        assertTrue(
            "both values should survive the rename, got:\n${myFixture.file.text}",
            myFixture.file.text.contains("Given I relocate 42 cukes from crate 7"),
        )
        assertTrue(
            "the definition should carry the new pattern, got:\n${definitions.text}",
            definitions.text.contains("I relocate"),
        )
    }

    /** `{string}` captures the quotes as well, so the quoted value has to come back intact. */
    fun testRenameKeepsQuotedStringParameters() {
        myFixture.addFileToProject(
            "CukeSteps.java",
            """
            import io.cucumber.java.en.Given;

            public class CukeSteps {
                @Given("the user {string} logs in")
                public void theUserLogsIn(String name) {}
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "test.feature",
            """
            Feature: Login
              Scenario: Signing in
                Given the user "bob" lo<caret>gs in
            """.trimIndent(),
        )

        val definition = org.jetbrains.plugins.cucumber.CucumberUtil
            .getCucumberStepReference(stepAtCaret())!!
            .resolveToDefinition()!!
        // Whatever the rename does, it starts from this regex — so it has to describe the quotes.
        val regex = definition.cucumberRegex!!
        assertTrue("expected {string} to become a capturing group, got: $regex", regex.contains("("))

        RenameProcessor(project, stepAtCaret(), regex.removePrefix("^").removeSuffix("$")
            .replace(" logs in", " signs in"), false, false).run()

        assertTrue(
            "the quoted value should survive, got:\n${myFixture.file.text}",
            myFixture.file.text.contains("""Given the user "bob" signs in"""),
        )
    }

    /**
     * Known limitation, pinned so it cannot change unnoticed.
     *
     * A Scenario Outline step holds `<count>` where a value would go, and the IDE's rename matches
     * each usage against the *old* pattern to recover its values —
     * `CucumberStepRenameProcessor.getNewStepName` returns the step unchanged when that match fails.
     * `<count>` never matches `(-?\d+)`, so outline steps are skipped while ordinary steps and the
     * step definition are rewritten. A definition used by both ends up matching only some of them.
     *
     * This is the Gherkin plugin's own logic and stock Cucumber for Java behaves the same way.
     * Fixing it means taking over the rename processor and matching against the substituted step
     * text instead.
     */
    fun testScenarioOutlineStepsAreLeftBehindByRename() {
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
        myFixture.configureByText(
            "test.feature",
            """
            Feature: Cukes
              Scenario Outline: Eating
                Given I have <count> cu<caret>kes

                Examples:
                  | count |
                  | 42    |
                  | 7     |
            """.trimIndent(),
        )

        RenameProcessor(project, stepAtCaret(), """I have (-?\d+) pineapples""", false, false).run()

        val featureText = myFixture.file.text
        assertTrue(
            "outline steps are skipped by rename; if this now passes, the limitation is fixed and " +
                "the documentation should say so. Got:\n$featureText",
            featureText.contains("Given I have <count> cukes"),
        )
        assertFalse(
            "the outline step was not expected to be reworded, got:\n$featureText",
            featureText.contains("pineapples"),
        )

        // The saving grace: the step left behind no longer matches anything, and says so straight
        // away rather than failing later in a test run.
        myFixture.enableInspections(UndefinedStepInspection())
        val reported = myFixture.doHighlighting().mapNotNull { it.description }
        assertTrue(
            "the step left behind should be reported as undefined, got: $reported",
            reported.any { it.contains("Undefined step") },
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
