package dev.kristian.cucumberfast.steps

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.JavaDirectoryService
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.codeStyle.JavaCodeStyleManager
import org.jetbrains.plugins.cucumber.AbstractStepDefinitionCreator
import org.jetbrains.plugins.cucumber.psi.GherkinStep

/**
 * Backs the "Create step definition" quick fix: creates the container class when there is none, and
 * writes the method itself.
 */
class JavaStepDefinitionCreator : AbstractStepDefinitionCreator() {

    override fun createStepDefinitionContainer(directory: PsiDirectory, name: String): PsiFile =
        JavaDirectoryService.getInstance().createClass(directory, name).containingFile

    override fun createStepDefinition(step: GherkinStep, file: PsiFile, withTemplate: Boolean): Boolean {
        val target = (file as? PsiClassOwner)?.classes?.firstOrNull() ?: return false
        val stepText = step.substitutedName ?: step.name ?: return false
        val snippet = StepSnippet.forStepText(stepText)
        val annotation = annotationFor(step)

        val write = Runnable { addMethod(target, annotation, snippet) }
        if (ApplicationManager.getApplication().isWriteAccessAllowed) {
            write.run()
        } else {
            WriteCommandAction.runWriteCommandAction(file.project, "Create Step Definition", null, write, file)
        }
        return true
    }

    private fun addMethod(target: PsiClass, annotation: String, snippet: StepSnippet.Snippet) {
        val project = target.project
        val factory = JavaPsiFacade.getElementFactory(project)
        val parameters = snippet.parameters.joinToString(", ") { "${it.type} ${it.name}" }
        val methodText = buildString {
            append("@io.cucumber.java.en.").append(annotation)
            append("(\"").append(escapeForJavaString(snippet.expression)).append("\")\n")
            append("public void ").append(snippet.methodName).append('(').append(parameters).append(") {\n")
            append("    throw new io.cucumber.java.PendingException();\n")
            append("}\n")
        }
        val method = factory.createMethodFromText(methodText, target)
        val added = target.add(method) as? PsiMethod ?: return
        // The generated text is fully qualified; let the IDE add the imports it prefers.
        JavaCodeStyleManager.getInstance(project).shortenClassReferences(added)
    }

    /**
     * `And`, `But` and `*` continue whatever came before them, which the annotation cannot express;
     * `Given` is the safe choice, and Cucumber matches on the expression regardless of keyword.
     * Localized feature files also get the English annotation, which compiles the same.
     */
    private fun annotationFor(step: GherkinStep): String =
        when (step.keyword.text.trim()) {
            "When" -> "When"
            "Then" -> "Then"
            else -> "Given"
        }

    private fun escapeForJavaString(text: String): String =
        text.replace("\\", "\\\\").replace("\"", "\\\"")

    override fun getDefaultStepFileName(step: GherkinStep): String {
        val featureName = step.containingFile.virtualFile?.nameWithoutExtension ?: return "StepDefs"
        return featureName.split('_', '-', ' ')
            .filter { it.isNotEmpty() }
            .joinToString("") { it.replaceFirstChar(Char::uppercaseChar) } + "Steps"
    }
}
