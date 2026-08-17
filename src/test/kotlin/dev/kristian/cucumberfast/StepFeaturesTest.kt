package dev.kristian.cucumberfast

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.kristian.cucumberfast.inspections.AmbiguousStepInspection
import dev.kristian.cucumberfast.inspections.UnusedStepDefinitionInspection
import dev.kristian.cucumberfast.steps.JavaStepDefinitionCreator
import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import dev.kristian.cucumberfast.steps.StepUsages
import org.jetbrains.plugins.cucumber.psi.GherkinStep

/** End-to-end checks for the features layered on top of step resolution. */
class StepFeaturesTest : BasePlatformTestCase() {

    fun testCustomParameterTypeResolvesTheStep() {
        myFixture.addFileToProject(
            "Types.java",
            """
            import io.cucumber.java.ParameterType;

            public class Types {
                @ParameterType("red|blue")
                public String colour(String name) { return name; }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "CukeSteps.java",
            """
            import io.cucumber.java.en.Given;

            public class CukeSteps {
                @Given("I have a {colour} cuke")
                public void iHaveAColouredCuke(String colour) {}
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "test.feature",
            """
            Feature: Cukes
              Scenario: Colours
                Given I have a red cu<caret>ke
            """.trimIndent(),
        )

        assertEquals("iHaveAColouredCuke", (resolveAtCaret() as? PsiMethod)?.name)
    }

    fun testCustomParameterTypeStillRejectsAnUnmatchedStep() {
        myFixture.addFileToProject(
            "Types.java",
            """
            import io.cucumber.java.ParameterType;

            public class Types {
                @ParameterType("red|blue")
                public String colour(String name) { return name; }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "CukeSteps.java",
            """
            import io.cucumber.java.en.Given;

            public class CukeSteps {
                @Given("I have a {colour} cuke")
                public void iHaveAColouredCuke(String colour) {}
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "test.feature",
            """
            Feature: Cukes
              Scenario: Colours
                Given I have a green cu<caret>ke
            """.trimIndent(),
        )

        assertNull(resolveAtCaret())
    }

    fun testLambdaStepDefinitionResolves() {
        myFixture.addFileToProject(
            "CukeSteps.java",
            """
            import io.cucumber.java8.En;

            public class CukeSteps implements En {
                public CukeSteps() {
                    Given("I have {int} cukes", (Integer count) -> {});
                }
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "test.feature",
            """
            Feature: Cukes
              Scenario: Eating
                Given I have 42 cu<caret>kes
            """.trimIndent(),
        )

        val resolved = resolveAtCaret()
        val call = PsiTreeUtil.getParentOfType(resolved, PsiMethodCallExpression::class.java, false)
        assertNotNull("expected to land on the registering call, got $resolved", call)
        assertTrue(call!!.text.startsWith("Given("))
    }

    fun testLocalizedStepDefinitionResolves() {
        myFixture.addFileToProject(
            "GurkenSteps.java",
            """
            import io.cucumber.java.de.Angenommen;

            public class GurkenSteps {
                @Angenommen("ich habe {int} Gurken")
                public void ichHabeGurken(int anzahl) {}
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "test.feature",
            """
            # language: de
            Funktionalität: Gurken
              Szenario: Essen
                Angenommen ich habe 42 Gur<caret>ken
            """.trimIndent(),
        )

        assertEquals("ichHabeGurken", (resolveAtCaret() as? PsiMethod)?.name)
    }

    fun testUnusedStepDefinitionIsReported() {
        myFixture.addFileToProject(
            "test.feature",
            """
            Feature: Cukes
              Scenario: Eating
                Given I have 42 cukes
            """.trimIndent(),
        )
        myFixture.enableInspections(UnusedStepDefinitionInspection())
        myFixture.configureByText(
            "CukeSteps.java",
            """
            import io.cucumber.java.en.Given;

            public class CukeSteps {
                @Given("I have {int} cukes")
                public void used(int count) {}

                @Given("nobody writes this step")
                public void unused() {}
            }
            """.trimIndent(),
        )

        val reported = myFixture.doHighlighting()
            .mapNotNull { it.description }
            .filter { it.contains("not used by any feature file") }
        assertEquals("expected exactly one unused step definition, got $reported", 1, reported.size)
    }

    fun testAmbiguousStepIsReported() {
        myFixture.addFileToProject(
            "CukeSteps.java",
            """
            import io.cucumber.java.en.Given;

            public class CukeSteps {
                @Given("I have {int} cukes")
                public void byCount(int count) {}

                @Given("I have {word} cukes")
                public void byWord(String word) {}
            }
            """.trimIndent(),
        )
        myFixture.enableInspections(AmbiguousStepInspection())
        myFixture.configureByText(
            "test.feature",
            """
            Feature: Cukes
              Scenario: Eating
                Given I have 42 cukes
            """.trimIndent(),
        )

        val reported = myFixture.doHighlighting().mapNotNull { it.description }
        assertTrue(
            "expected an ambiguity warning, got $reported",
            reported.any { it.contains("matched by 2 step definitions") },
        )
    }

    fun testAnUnambiguousStepIsNotReported() {
        myFixture.addFileToProject(
            "CukeSteps.java",
            """
            import io.cucumber.java.en.Given;

            public class CukeSteps {
                @Given("I have {int} cukes")
                public void byCount(int count) {}
            }
            """.trimIndent(),
        )
        myFixture.enableInspections(AmbiguousStepInspection())
        myFixture.configureByText(
            "test.feature",
            """
            Feature: Cukes
              Scenario: Eating
                Given I have 42 cukes
            """.trimIndent(),
        )

        val reported = myFixture.doHighlighting().mapNotNull { it.description }
        assertFalse(reported.any { it.contains("step definitions") })
    }

    fun testUsageCountIsShared() {
        myFixture.addFileToProject(
            "test.feature",
            """
            Feature: Cukes
              Scenario: Eating
                Given I have 42 cukes
                Given I have 7 cukes
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
        assertTrue(StepUsages.isStepDefinition(method))
        assertEquals(2, StepUsages.of(method).size)
    }

    fun testQuickFixGeneratesTheMethod() {
        val stepDefinitions = myFixture.addFileToProject("CukeSteps.java", "public class CukeSteps {\n}")
        myFixture.configureByText(
            "test.feature",
            """
            Feature: Cukes
              Scenario: Eating
                Given I have 42 cukes in my "big" be<caret>lly
            """.trimIndent(),
        )
        val step = stepAtCaret()

        WriteCommandAction.runWriteCommandAction(project) {
            JavaStepDefinitionCreator().createStepDefinition(step, stepDefinitions, false)
        }

        val generated = stepDefinitions.text
        assertTrue(
            "generated source was:\n$generated",
            generated.contains("""("I have {int} cukes in my {string} belly")"""),
        )
        assertTrue(generated.contains("i_have_cukes_in_my_belly(Integer int1, String string1)"))
        assertTrue(generated.contains("PendingException"))
    }

    private fun stepAtCaret(): GherkinStep =
        PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(myFixture.caretOffset), GherkinStep::class.java)
            ?: error("no Gherkin step at the caret")

    /** Goes through the same entry point as Ctrl+click, so it tests whichever reference wins. */
    private fun resolveAtCaret() =
        GotoDeclarationAction.findTargetElement(project, myFixture.editor, myFixture.caretOffset)
}
