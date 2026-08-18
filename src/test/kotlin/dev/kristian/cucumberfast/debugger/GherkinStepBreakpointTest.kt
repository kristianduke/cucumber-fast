package dev.kristian.cucumberfast.debugger

import com.intellij.debugger.ui.breakpoints.BreakpointManager
import com.intellij.openapi.application.WriteAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import org.jetbrains.java.debugger.breakpoints.properties.JavaLineBreakpointProperties

/**
 * Breakpoints placed on steps in a feature file.
 *
 * What can be checked without a running JVM is where the breakpoint would be installed: which lines
 * accept one, which Java line the request goes on, and which method's locations it will take. The
 * JDI half — the class-prepare request and the check for which step is running — needs a live debug
 * process and is not exercised here.
 */
class GherkinStepBreakpointTest : BasePlatformTestCase() {

    private val stepDefinitions = """
        import io.cucumber.java.en.Given;
        import io.cucumber.java.en.Then;

        public class CukeSteps {
            @Given("I have {int} cukes")
            public void iHaveCukes(int count) {
                System.out.println(count);
            }

            @Then("the belly is full")
            public void theBellyIsFull() {
            }
        }
    """.trimIndent()

    private val type = GherkinStepBreakpointType()

    fun testABreakpointGoesOnAStepLine() {
        val feature = configureFeature(
            """
            Feature: Cukes
              Scenario: Eating
                Given I have 42 cukes
            """.trimIndent(),
        )

        assertTrue("the step line should accept a breakpoint", canPutAt(feature, "Given I have 42 cukes"))
    }

    fun testNoBreakpointOnTheLinesAroundAStep() {
        val feature = configureFeature(
            """
            Feature: Cukes
              # a comment

              Scenario: Eating
                Given I have 42 cukes
            """.trimIndent(),
        )

        assertFalse("Feature:", canPutAt(feature, "Feature: Cukes"))
        assertFalse("a comment", canPutAt(feature, "# a comment"))
        assertFalse("Scenario:", canPutAt(feature, "Scenario: Eating"))
        assertFalse("blank line", type.canPutAt(feature.virtualFile, 2, project))
    }

    /** A step with a table spans several lines; only the one its keyword is on runs anything. */
    fun testNoBreakpointOnATableRowOfAStep() {
        val feature = configureFeature(
            """
            Feature: Cukes
              Scenario: Eating
                Given I have these cukes:
                  | count |
                  | 42    |
            """.trimIndent(),
        )

        assertTrue(canPutAt(feature, "Given I have these cukes:"))
        assertFalse(canPutAt(feature, "| count |"))
        assertFalse(canPutAt(feature, "| 42    |"))
    }

    fun testTheRequestGoesOnTheFirstLineOfTheStepDefinition() {
        val definitions = myFixture.addFileToProject("CukeSteps.java", stepDefinitions)
        val feature = configureFeature(
            """
            Feature: Cukes
              Scenario: Eating
                Given I have 42 cukes
            """.trimIndent(),
        )

        val target = resolve(feature, "Given I have 42 cukes")

        assertNotNull("the step should resolve to a definition", target)
        assertEquals("CukeSteps.java", target!!.position.file.name)
        assertEquals(lineOf(definitions, "System.out.println(count);"), target.position.line)
        assertEquals(StepDefinitionBody.Method("iHaveCukes"), target.body)
    }

    /** An empty definition has no statement to stop at, so the closing brace stands in for one. */
    fun testAnEmptyStepDefinitionStopsAtItsClosingBrace() {
        val definitions = myFixture.addFileToProject("CukeSteps.java", stepDefinitions)
        val feature = configureFeature(
            """
            Feature: Cukes
              Scenario: Eating
                Then the belly is full
            """.trimIndent(),
        )

        val target = resolve(feature, "Then the belly is full")

        assertNotNull(target)
        assertEquals(lineOf(definitions, "public void theBellyIsFull() {") + 1, target!!.position.line)
    }

    fun testALambdaStepDefinitionStopsInsideTheLambda() {
        val definitions = myFixture.addFileToProject(
            "LambdaSteps.java",
            """
            import io.cucumber.java8.En;

            public class LambdaSteps implements En {
                public LambdaSteps() {
                    Given("I have {int} cukes", (Integer count) -> {
                        System.out.println(count);
                    });
                }
            }
            """.trimIndent(),
        )
        val feature = configureFeature(
            """
            Feature: Cukes
              Scenario: Eating
                Given I have 42 cukes
            """.trimIndent(),
        )

        val target = resolve(feature, "Given I have 42 cukes")

        assertNotNull(target)
        assertEquals(lineOf(definitions, "System.out.println(count);"), target!!.position.line)
        assertEquals(StepDefinitionBody.Lambda, target.body)
    }

    /**
     * A `lambda$...` method and the constructor registering it can report the same line, so the
     * body decides which of them the request is allowed on. Without that the breakpoint would also
     * stop while the glue is being loaded.
     */
    fun testOnlyTheDefinitionsOwnMethodIsAccepted() {
        assertTrue(StepDefinitionBody.Lambda.accepts("lambda${'$'}new${'$'}0"))
        assertFalse(StepDefinitionBody.Lambda.accepts("<init>"))
        assertTrue(StepDefinitionBody.Method("iHaveCukes").accepts("iHaveCukes"))
        assertFalse(StepDefinitionBody.Method("iHaveCukes").accepts("theBellyIsFull"))
    }

    /** Outline steps carry placeholders; they resolve through the substituted text, as elsewhere. */
    fun testAnOutlineStepResolvesThroughItsExamples() {
        myFixture.addFileToProject("CukeSteps.java", stepDefinitions)
        val feature = configureFeature(
            """
            Feature: Cukes
              Scenario Outline: Eating
                Given I have <count> cukes

                Examples:
                  | count |
                  | 42    |
            """.trimIndent(),
        )

        val target = resolve(feature, "Given I have <count> cukes")

        assertNotNull("an outline step should resolve like any other", target)
        assertEquals(StepDefinitionBody.Method("iHaveCukes"), target!!.body)
    }

    fun testAStepNothingDefinesHasNoTarget() {
        myFixture.addFileToProject("CukeSteps.java", stepDefinitions)
        val feature = configureFeature(
            """
            Feature: Cukes
              Scenario: Eating
                Given I have a pineapple
            """.trimIndent(),
        )

        assertNull(resolve(feature, "Given I have a pineapple"))
    }

    /**
     * Which line Cucumber reports depends on how the run was pointed at the features: a
     * `classpath:` URI when they are on the classpath, an absolute `file:` one when they are a
     * path. Both have to name the same step.
     */
    fun testTheRunningStepIsRecognisedFromEitherUriForm() {
        val identity = RunningStepIdentity(path = "features/eat.feature", fileName = "eat.feature", line = 4)

        assertTrue(identity.matches("classpath:features/eat.feature", 4))
        assertTrue(identity.matches("file:///home/dev/src/test/resources/features/eat.feature", 4))
        assertTrue(identity.matches("file:///C:/dev/src/test/resources/features/eat.feature", 4))
        // A layout the relative path does not cover still matches on the file name alone.
        assertTrue(identity.matches("classpath:eat.feature", 4))

        assertFalse("another step in the same file", identity.matches("classpath:features/eat.feature", 5))
        assertFalse("the same line of another file", identity.matches("classpath:features/drink.feature", 4))
        assertFalse("a file whose name only ends the same way", identity.matches("classpath:great-eat.feature", 4))
    }

    /**
     * The whole wiring, short of a running JVM: the registered type builds a Java breakpoint, and
     * that breakpoint reports the step definition's position rather than the feature line the
     * gutter icon sits on. Everything the JVM debugger does with it — the class-prepare request,
     * the search for locations — is driven by exactly those two answers.
     */
    fun testTheJavaBreakpointReportsTheStepDefinitionsPosition() {
        val definitions = myFixture.addFileToProject("CukeSteps.java", stepDefinitions)
        val feature = configureFeature(
            """
            Feature: Cukes
              Scenario: Eating
                Given I have 42 cukes
            """.trimIndent(),
        )
        val registered = XDebuggerUtil.getInstance().findBreakpointType(GherkinStepBreakpointType::class.java)
        assertNotNull("the breakpoint type should be registered", registered)

        val manager = XDebuggerManager.getInstance(project).breakpointManager
        val xBreakpoint = WriteAction.compute<XLineBreakpoint<JavaLineBreakpointProperties>, RuntimeException> {
            manager.addLineBreakpoint(
                registered,
                feature.virtualFile.url,
                lineOf(feature, "Given I have 42 cukes"),
                JavaLineBreakpointProperties(),
            )
        }
        try {
            val javaBreakpoint = BreakpointManager.getJavaBreakpoint(xBreakpoint) as? GherkinStepBreakpoint
            assertNotNull("the type should produce a Java breakpoint", javaBreakpoint)

            javaBreakpoint!!.reload()

            val expected = lineOf(definitions, "System.out.println(count);")
            assertEquals("CukeSteps.java", javaBreakpoint.sourcePosition?.file?.name)
            assertEquals(expected, javaBreakpoint.sourcePosition?.line)
            assertEquals(expected, javaBreakpoint.lineIndex)
            assertEquals("test.feature:3 in iHaveCukes()", javaBreakpoint.displayName)
        } finally {
            WriteAction.run<RuntimeException> { manager.removeBreakpoint(xBreakpoint) }
        }
    }

    private fun configureFeature(text: String): PsiFile = myFixture.configureByText("test.feature", text)

    private fun canPutAt(feature: PsiFile, lineText: String): Boolean =
        type.canPutAt(feature.virtualFile, lineOf(feature, lineText), project)

    private fun resolve(feature: PsiFile, lineText: String): GherkinStepTarget? =
        GherkinStepTarget.resolve(project, feature.virtualFile, lineOf(feature, lineText))

    private fun lineOf(file: PsiFile, text: String): Int {
        val offset = file.text.indexOf(text)
        assertTrue("$text is not in ${file.name}", offset >= 0)
        val document = PsiDocumentManager.getInstance(project).getDocument(file)!!
        return document.getLineNumber(offset)
    }
}
