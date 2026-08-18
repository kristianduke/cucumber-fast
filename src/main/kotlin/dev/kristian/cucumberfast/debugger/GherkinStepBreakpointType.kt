package dev.kristian.cucumberfast.debugger

import com.intellij.debugger.ui.breakpoints.Breakpoint
import com.intellij.debugger.ui.breakpoints.JavaLineBreakpointTypeBase
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.xdebugger.breakpoints.XBreakpoint
import org.jetbrains.java.debugger.breakpoints.properties.JavaLineBreakpointProperties
import org.jetbrains.plugins.cucumber.psi.GherkinFile
import org.jetbrains.plugins.cucumber.psi.GherkinFileType

/**
 * Lets a breakpoint be placed on a step in a feature file.
 *
 * Extending the Java base is what makes the JVM debugger willing to carry it: the breakpoint keeps
 * Java's condition editor, its filters panel and its suspend policies, and — together with
 * [GherkinStepBreakpointHandlerFactory] — is handed to every Java debug process, whether the run
 * was started from JUnit, Gradle, Maven or a remote attach.
 */
class GherkinStepBreakpointType : JavaLineBreakpointTypeBase<JavaLineBreakpointProperties>(ID, TITLE) {

    override fun createBreakpointProperties(file: VirtualFile, line: Int) = JavaLineBreakpointProperties()

    /** Only on a line that starts a step; the feature's other lines run no code of their own. */
    override fun canPutAt(file: VirtualFile, line: Int, project: Project): Boolean {
        if (!FileTypeRegistry.getInstance().isFileOfType(file, GherkinFileType.INSTANCE)) return false
        return ReadAction.compute<Boolean, RuntimeException> {
            val psiFile = PsiManager.getInstance(project).findFile(file) as? GherkinFile ?: return@compute false
            GherkinStepTarget.stepAt(psiFile, line) != null
        }
    }

    override fun createJavaBreakpoint(
        project: Project,
        breakpoint: XBreakpoint<JavaLineBreakpointProperties>,
    ): Breakpoint<JavaLineBreakpointProperties> = GherkinStepBreakpoint(project, breakpoint)

    private companion object {
        const val ID = "cucumber-fast-gherkin-step"
        const val TITLE = "Cucumber steps"
    }
}
