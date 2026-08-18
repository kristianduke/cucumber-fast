package dev.kristian.cucumberfast.debugger

import com.intellij.debugger.SourcePosition
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiCodeBlock
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLambdaExpression
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import dev.kristian.cucumberfast.reference.fastStepReference
import org.jetbrains.plugins.cucumber.psi.GherkinFile
import org.jetbrains.plugins.cucumber.psi.GherkinStep
import org.jetbrains.plugins.cucumber.psi.GherkinStepsHolder

/**
 * Which JVM method a step definition's body ends up in, so a location found by line number can be
 * checked before a request is put on it.
 *
 * A line holds more than one method whenever a lambda shares it with the call registering it — a
 * one-line `Given("...", () -> check());` compiles to a synthetic `lambda$new$0` *and* a
 * constructor, both reporting that line. Without this filter the breakpoint would also stop while
 * step definitions are being registered, before any scenario runs.
 */
internal sealed interface StepDefinitionBody {

    fun accepts(jvmMethodName: String): Boolean

    /** An annotated method: the JVM method carries its name. */
    data class Method(val name: String) : StepDefinitionBody {
        override fun accepts(jvmMethodName: String): Boolean = jvmMethodName == name
    }

    /** An `io.cucumber.java8` lambda, which javac compiles to a synthetic `lambda$...` method. */
    data object Lambda : StepDefinitionBody {
        override fun accepts(jvmMethodName: String): Boolean = jvmMethodName.startsWith("lambda$")
    }
}

/**
 * The step as Cucumber reports it at runtime: which feature file, and which line in it.
 *
 * Used to tell one usage of a step definition from another. A definition is normally shared by
 * several steps, so the JVM breakpoint alone would stop on all of them; this is what narrows it
 * back down to the step the breakpoint was actually put on.
 */
internal data class RunningStepIdentity(
    /** The feature file's path relative to its source root — what a `classpath:` URI carries. */
    val path: String,
    val fileName: String,
    /** One-based, as Cucumber counts lines. */
    val line: Int,
) {

    /**
     * Whether the step Cucumber is running is this one.
     *
     * The URI is matched by suffix because Cucumber reports whatever the runner was pointed at:
     * `classpath:features/eat.feature` when the features are on the classpath, an absolute
     * `file:///...` URI when they are a path. Both end with the path this step's file has under its
     * source root, and the file name alone is the fallback for a layout that produces neither.
     */
    fun matches(uri: String, runningLine: Int): Boolean {
        if (runningLine != line) return false
        val normalized = uri.replace('\\', '/')
        return normalized.endsWithSegment(path) || normalized.endsWithSegment(fileName)
    }

    private fun String.endsWithSegment(suffix: String): Boolean =
        this == suffix || endsWith("/$suffix") || endsWith(":$suffix")
}

/**
 * A breakpoint set on a Gherkin step, translated into the place the JVM can actually stop at.
 *
 * A `.feature` line has no bytecode, so the request goes on the first executable line of the step
 * definition the step resolves to — which is where the pause was wanted anyway: inside the Java
 * method, before it does its work.
 */
internal class GherkinStepTarget(
    val position: SourcePosition,
    val body: StepDefinitionBody,
    val identity: RunningStepIdentity,
    /** For the breakpoint's label in the gutter tooltip and the breakpoints dialog. */
    val definitionName: String?,
) {

    companion object {

        /**
         * Resolves the step on [line] of [file] to the position a JVM breakpoint belongs at, or
         * null when there is no step there or nothing defines it.
         *
         * Called from the debugger's reload, under a read action.
         */
        fun resolve(project: Project, file: VirtualFile, line: Int): GherkinStepTarget? {
            if (DumbService.isDumb(project)) return null
            val psiFile = PsiManager.getInstance(project).findFile(file) as? GherkinFile ?: return null
            val step = stepAt(psiFile, line) ?: return null
            // Ambiguity is a runtime failure in Cucumber and its own inspection reports it; here the
            // first definition that resolves is as good a guess as any.
            val definition = step.fastStepReference()
                ?.definitions()
                ?.firstNotNullOfOrNull { it.element }
                ?: return null
            val (anchor, body) = anchorIn(definition) ?: return null
            val position = SourcePosition.createFromElement(anchor) ?: return null

            return GherkinStepTarget(
                position = position,
                body = body,
                identity = identityOf(project, file, line),
                definitionName = (body as? StepDefinitionBody.Method)?.name,
            )
        }

        /** The step starting on [line], if that line starts one at all. */
        fun stepAt(psiFile: PsiFile, line: Int): GherkinStep? {
            val document = PsiDocumentManager.getInstance(psiFile.project).getDocument(psiFile) ?: return null
            if (line < 0 || line >= document.lineCount) return null

            val end = document.getLineEndOffset(line)
            var offset = document.getLineStartOffset(line)
            while (offset < end) {
                val element = psiFile.findElementAt(offset)
                if (element == null) {
                    offset++
                    continue
                }
                val step = PsiTreeUtil.getParentOfType(element, GherkinStep::class.java)
                // A step carrying a table or doc string spans several lines; only the line its
                // keyword is on counts, so a breakpoint cannot be dropped on a table row.
                if (step != null &&
                    step.parent is GherkinStepsHolder &&
                    document.getLineNumber(step.textOffset) == line
                ) {
                    return step
                }
                offset = maxOf(element.textRange.endOffset, offset + 1)
            }
            return null
        }

        /**
         * The first line of the definition's body — the method's first statement, or the lambda's.
         * An empty body has no statement to stop at, so its closing brace is used: that is where
         * javac puts the implicit `return`.
         */
        private fun anchorIn(definition: PsiElement): Pair<PsiElement, StepDefinitionBody>? = when (definition) {
            is PsiMethod ->
                definition.body
                    ?.let(::firstExecutable)
                    ?.let { it to StepDefinitionBody.Method(definition.name) }

            // A lambda step definition resolves to the call that registers it; the code to stop in
            // is the lambda handed to that call, not the registration itself.
            is PsiMethodCallExpression -> {
                val lambda = definition.argumentList.expressions
                    .filterIsInstance<PsiLambdaExpression>()
                    .firstOrNull()
                when (val body = lambda?.body) {
                    is PsiCodeBlock -> firstExecutable(body)?.let { it to StepDefinitionBody.Lambda }
                    is PsiExpression -> body to StepDefinitionBody.Lambda
                    else -> null
                }
            }

            else -> null
        }

        private fun firstExecutable(block: PsiCodeBlock): PsiElement? =
            block.statements.firstOrNull() ?: block.rBrace

        private fun identityOf(project: Project, file: VirtualFile, line: Int): RunningStepIdentity {
            val fileIndex = ProjectFileIndex.getInstance(project)
            val root = fileIndex.getSourceRootForFile(file) ?: fileIndex.getContentRootForFile(file)
            val path = root?.let { VfsUtilCore.getRelativePath(file, it, '/') } ?: file.name
            return RunningStepIdentity(path = path, fileName = file.name, line = line + 1)
        }
    }
}
