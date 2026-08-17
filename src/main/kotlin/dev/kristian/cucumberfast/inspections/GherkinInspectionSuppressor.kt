package dev.kristian.cucumberfast.inspections

import com.intellij.codeInspection.InspectionSuppressor
import com.intellij.codeInspection.SuppressQuickFix
import com.intellij.psi.PsiElement

/**
 * Silences the Gherkin plugin's own undefined-step inspection.
 *
 * That inspection asks the Gherkin plugin's reference to resolve, and this plugin deliberately
 * leaves that reference resolving to nothing so it does not repeat the linear lookup. Every step
 * would therefore be reported as undefined. [UndefinedStepInspection] reports the same problem from
 * the indexed lookup instead.
 */
class GherkinInspectionSuppressor : InspectionSuppressor {

    override fun isSuppressedFor(element: PsiElement, toolId: String): Boolean = toolId == SUPERSEDED_INSPECTION

    override fun getSuppressActions(element: PsiElement?, toolId: String): Array<SuppressQuickFix> =
        SuppressQuickFix.EMPTY_ARRAY

    private companion object {
        /** `CucumberStepInspection.getShortName()` in the Gherkin plugin. */
        const val SUPERSEDED_INSPECTION = "CucumberUndefinedStep"
    }
}
