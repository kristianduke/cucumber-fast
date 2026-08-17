package dev.kristian.cucumberfast

import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.psi.PsiMethod
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Every parameter type Cucumber defines out of the box, checked by resolving a real step through it.
 *
 * `{int}` and `{string}` are the ones everybody uses, so they are the ones that get noticed when
 * they break. The rest fail quietly: the step simply reads as undefined.
 */
class ParameterTypeCoverageTest : BasePlatformTestCase() {

    /** Each entry: the parameter type, a step that should match, and one that should not. */
    private val builtIns = listOf(
        Triple("int", "42", "forty"),
        Triple("float", "1.5", "abc"),
        Triple("word", "hello", "two words"),
        Triple("string", "\"hi there\"", "unquoted"),
        Triple("bigdecimal", "1.5", "abc"),
        Triple("biginteger", "42", "forty"),
        Triple("byte", "8", "eight"),
        Triple("short", "16", "sixteen"),
        Triple("long", "64", "sixtyfour"),
        Triple("double", "2.5", "abc"),
    )

    private fun addDefinitions() {
        val methods = builtIns.joinToString("\n\n") { (type, _, _) ->
            """
                @Given("the $type value is {$type} exactly")
                public void ${type}Step(Object value) {}
            """.trimIndent()
        }
        myFixture.addFileToProject(
            "CukeSteps.java",
            """
            import io.cucumber.java.en.Given;

            public class CukeSteps {
            $methods

                @Given("the anonymous value is {} exactly")
                public void anonymousStep(Object value) {}
            }
            """.trimIndent(),
        )
    }

    private fun resolveStep(stepText: String): PsiMethod? {
        myFixture.configureByText(
            "test.feature",
            "Feature: F\n  Scenario: S\n    Given $stepText\n",
        )
        val caret = myFixture.file.text.indexOf(stepText) + 2
        return GotoDeclarationAction.findTargetElement(project, myFixture.editor, caret) as? PsiMethod
    }

    fun testEveryBuiltInParameterTypeMatches() {
        addDefinitions()
        val unmatched = builtIns.filter { (type, matching, _) ->
            resolveStep("the $type value is $matching exactly")?.name != "${type}Step"
        }
        assertTrue("these parameter types did not resolve a matching step: ${unmatched.map { it.first }}", unmatched.isEmpty())
    }

    fun testEveryBuiltInParameterTypeRejectsWhatItShould() {
        addDefinitions()
        val tooLenient = builtIns.filter { (type, _, notMatching) ->
            resolveStep("the $type value is $notMatching exactly") != null
        }
        assertTrue("these parameter types matched a value they should not: ${tooLenient.map { it.first }}", tooLenient.isEmpty())
    }

    fun testTheAnonymousParameterTypeMatchesAnything() {
        addDefinitions()
        assertEquals("anonymousStep", resolveStep("the anonymous value is whatever exactly")?.name)
        assertEquals("anonymousStep", resolveStep("the anonymous value is 42 exactly")?.name)
    }

    fun testNegativeAndSignedNumbersMatch() {
        addDefinitions()
        assertEquals("intStep", resolveStep("the int value is -42 exactly")?.name)
        assertEquals("floatStep", resolveStep("the float value is -1.5 exactly")?.name)
    }
}
