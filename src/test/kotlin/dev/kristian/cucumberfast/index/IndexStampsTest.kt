package dev.kristian.cucumberfast.index

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.kristian.cucumberfast.steps.StepSearch

/**
 * The index stamp is only re-read when PSI, VFS, roots or dumb mode have moved, so each of the
 * ways a step definition can change has to still reach the caches built on it.
 */
class IndexStampsTest : BasePlatformTestCase() {

    private fun steps(vararg patterns: String) =
        "import io.cucumber.java.en.Given;\n\npublic class CukeSteps {\n" +
            patterns.withIndex().joinToString("") { (i, p) -> "    @Given(\"$p\")\n    public void s$i() {}\n" } +
            "}\n"

    private fun definitionsFor(stepText: String) =
        StepSearch.definitionsForStep(ModuleUtilCore.findModuleForPsiElement(myFixture.file)!!, stepText)

    override fun setUp() {
        super.setUp()
        myFixture.configureByText("test.feature", "Feature: F\n  Scenario: S\n    Given I have 42 cukes\n")
    }

    fun testUnchangedProjectReportsTheSameStamp() {
        myFixture.addFileToProject("CukeSteps.java", steps("I have {int} cukes"))
        val tracker = IndexStamps.tracker(project, JavaStepDefinitionIndex.NAME)
        val first = tracker.modificationCount
        assertEquals(first, tracker.modificationCount)
        assertEquals(first, tracker.modificationCount)
    }

    fun testANewStepDefinitionFileIsSeen() {
        myFixture.addFileToProject("CukeSteps.java", steps("I have {int} cukes"))
        assertEquals(1, definitionsFor("I have 42 cukes").size)
        assertEquals(0, definitionsFor("I eat 3 cukes").size)

        myFixture.addFileToProject("MoreSteps.java", steps("I eat {int} cukes").replace("CukeSteps", "MoreSteps"))

        assertEquals(1, definitionsFor("I eat 3 cukes").size)
    }

    fun testAnEditInTheEditorIsSeen() {
        val file = myFixture.addFileToProject("CukeSteps.java", steps("I have {int} cukes"))
        assertEquals(1, definitionsFor("I have 42 cukes").size)

        val document = PsiDocumentManager.getInstance(project).getDocument(file)!!
        WriteAction.run<RuntimeException> {
            document.setText(steps("I eat {int} cukes"))
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        assertEquals(0, definitionsFor("I have 42 cukes").size)
        assertEquals(1, definitionsFor("I eat 3 cukes").size)
    }

    /** A change made outside the IDE arrives through the VFS alone, without any PSI event first. */
    fun testAChangeOnDiskIsSeen() {
        val file = myFixture.addFileToProject("CukeSteps.java", steps("I have {int} cukes")).virtualFile
        assertEquals(1, definitionsFor("I have 42 cukes").size)

        WriteAction.run<Exception> { VfsUtil.saveText(file, steps("I eat {int} cukes")) }

        assertEquals(0, definitionsFor("I have 42 cukes").size)
        assertEquals(1, definitionsFor("I eat 3 cukes").size)
    }

    private fun parameterTypes(regex: String) =
        "import io.cucumber.java.ParameterType;\n\npublic class Types {\n" +
            "    @ParameterType(\"$regex\")\n    public String colour(String c) { return c; }\n}\n"

    /**
     * A `@ParameterType` usually lives in a file with no step definitions, so declaring one leaves
     * the step definition index alone — yet it changes what every `{colour}` matches.
     */
    fun testANewParameterTypeIsSeen() {
        myFixture.addFileToProject("CukeSteps.java", steps("I have a {colour} cuke"))
        assertEquals(1, definitionsFor("I have a red cuke").size)

        myFixture.addFileToProject("Types.java", parameterTypes("green|blue"))

        // `{colour}` was permissive while undeclared; once declared it means only what it says.
        assertEquals(0, definitionsFor("I have a red cuke").size)
        assertEquals(1, definitionsFor("I have a green cuke").size)
    }

    fun testAChangedParameterTypeIsSeen() {
        myFixture.addFileToProject("CukeSteps.java", steps("I have a {colour} cuke"))
        val types = myFixture.addFileToProject("Types.java", parameterTypes("green|blue")).virtualFile
        assertEquals(1, definitionsFor("I have a green cuke").size)

        WriteAction.run<Exception> { VfsUtil.saveText(types, parameterTypes("red|blue")) }

        assertEquals(0, definitionsFor("I have a green cuke").size)
        assertEquals(1, definitionsFor("I have a red cuke").size)
    }
}
