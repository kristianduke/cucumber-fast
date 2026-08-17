package dev.kristian.cucumberfast.steps

import com.intellij.psi.JavaDirectoryService
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import org.jetbrains.plugins.cucumber.AbstractStepDefinitionCreator
import org.jetbrains.plugins.cucumber.psi.GherkinStep

/**
 * Backs the "Create step definition" quick fix.
 *
 * Only the container class is generated for now — the method body itself is not written yet, so
 * the fix lands you in a new step definition class rather than on a generated method.
 */
class JavaStepDefinitionCreator : AbstractStepDefinitionCreator() {

    override fun createStepDefinitionContainer(directory: PsiDirectory, name: String): PsiFile =
        JavaDirectoryService.getInstance().createClass(directory, name).containingFile

    override fun getDefaultStepFileName(step: GherkinStep): String {
        val featureName = step.containingFile.virtualFile?.nameWithoutExtension ?: return "StepDefs"
        return featureName.split('_', '-', ' ')
            .filter { it.isNotEmpty() }
            .joinToString("") { it.replaceFirstChar(Char::uppercaseChar) } + "Steps"
    }
}
