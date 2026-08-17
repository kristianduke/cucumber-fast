package dev.kristian.cucumberfast.navigation

import com.intellij.codeInsight.codeVision.CodeVisionRelativeOrdering
import com.intellij.codeInsight.hints.codeVision.CodeVisionProviderBase
import com.intellij.ide.util.DefaultPsiElementCellRenderer
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.pom.Navigatable
import dev.kristian.cucumberfast.steps.StepUsages
import java.awt.event.MouseEvent

/**
 * "3 Gherkin steps" above a step definition method, clickable to the steps themselves.
 *
 * The count comes from [StepUsages], the same cached lookup the gutter marker uses, so showing it
 * costs nothing beyond what highlighting the method already does.
 */
class StepUsagesCodeVisionProvider : CodeVisionProviderBase() {

    override val id: String = ID

    override val name: String = "Cucumber step usages"

    override val relativeOrderings: List<CodeVisionRelativeOrdering> =
        listOf(CodeVisionRelativeOrdering.CodeVisionRelativeOrderingAfter("java.inheritors"))

    override fun acceptsFile(file: PsiFile): Boolean = file is PsiClassOwner

    override fun acceptsElement(element: PsiElement): Boolean =
        element is PsiMethod && !DumbService.isDumb(element.project) && StepUsages.isStepDefinition(element)

    override fun getHint(element: PsiElement, file: PsiFile): String? {
        val method = element as? PsiMethod ?: return null
        return when (val count = StepUsages.of(method).size) {
            0 -> "No Gherkin steps"
            1 -> "1 Gherkin step"
            else -> "$count Gherkin steps"
        }
    }

    override fun handleClick(editor: Editor, element: PsiElement, event: MouseEvent?) {
        val method = element as? PsiMethod ?: return
        val targets = StepUsages.resolve(method.project, StepUsages.of(method))
        when (targets.size) {
            0 -> Unit
            1 -> (targets.single() as? Navigatable)?.navigate(true)
            else -> JBPopupFactory.getInstance()
                .createPopupChooserBuilder(targets)
                .setTitle("Gherkin Steps")
                .setRenderer(DefaultPsiElementCellRenderer())
                .setItemChosenCallback { (it as? Navigatable)?.navigate(true) }
                .createPopup()
                .showInBestPositionFor(editor)
        }
    }

    companion object {
        const val ID: String = "cucumberfast.step.usages"
    }
}
