package dev.kristian.cucumberfast.inspections

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.DumbService
import com.intellij.psi.JavaElementVisitor
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiMethod
import dev.kristian.cucumberfast.steps.StepUsages

/**
 * Reports a step definition that no feature file uses.
 *
 * This is free: the gutter marker already asks [StepUsages] for the same method during the same
 * highlighting pass, and the answer is cached until the feature files change.
 *
 * Scope is the project, so a step definition used only from a feature file outside the project —
 * a shared suite pulled in as a dependency — would be reported wrongly. That is the same scope the
 * gutter marker uses, and is why this is a weak warning rather than an error.
 */
class UnusedStepDefinitionInspection : AbstractBaseJavaLocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : JavaElementVisitor() {
            override fun visitMethod(method: PsiMethod) {
                if (DumbService.isDumb(method.project)) return
                val info = StepUsages.info(method)
                if (!info.isStepDefinition) return
                // `@Given(SOME_CONSTANT)` cannot be matched against anything, so "no feature file
                // uses it" would say more about this plugin than about the code.
                if (!info.hasReadablePattern) return
                if (info.usages.isNotEmpty()) return

                val nameIdentifier = method.nameIdentifier ?: return
                holder.registerProblem(
                    nameIdentifier,
                    "Step definition is not used by any feature file",
                )
            }
        }
}
