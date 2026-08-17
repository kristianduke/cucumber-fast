package dev.kristian.cucumberfast.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import dev.kristian.cucumberfast.reference.fastStepReference
import dev.kristian.cucumberfast.steps.JavaStepDefinitionCreator
import dev.kristian.cucumberfast.steps.StepSearch
import org.jetbrains.plugins.cucumber.psi.GherkinElementVisitor
import org.jetbrains.plugins.cucumber.psi.GherkinStep
import org.jetbrains.plugins.cucumber.psi.GherkinStepsHolder

/**
 * Reports a step no step definition matches.
 *
 * This replaces the Gherkin plugin's own undefined-step inspection, which is suppressed by
 * [GherkinInspectionSuppressor]: that one resolves through the extension point, asking every
 * definition in the module about every step, on every highlighting pass.
 */
class UndefinedStepInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : GherkinElementVisitor() {
            override fun visitStep(step: GherkinStep) {
                if (step.parent !is GherkinStepsHolder) return
                if (DumbService.isDumb(step.project)) return

                val reference = step.fastStepReference() ?: return
                if (reference.definitions().any { it.element != null }) return

                val fixes = if (canCreateStepDefinition(step)) arrayOf<LocalQuickFix>(CreateStepDefinitionFix()) else emptyArray()
                holder.registerProblem(step, reference.rangeInElement, "Undefined step", *fixes)
            }
        }

    private fun canCreateStepDefinition(step: GherkinStep): Boolean =
        targetFile(step) != null

    /**
     * Where a generated step definition should go: the file that already holds the most of them.
     *
     * Deliberately conservative — with no existing step definitions anywhere, this plugin does not
     * guess at a package or source root, and simply offers no fix.
     */
    private fun targetFile(step: GherkinStep): PsiFile? {
        val module = ModuleUtilCore.findModuleForPsiElement(step) ?: return null
        return StepSearch.allDefinitions(module)
            .groupingBy { it.containingFile }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
            ?.takeIf { it.isWritable }
    }

    private inner class CreateStepDefinitionFix : LocalQuickFix {

        override fun getFamilyName(): String = "Create step definition"

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val step = descriptor.psiElement as? GherkinStep ?: return
            val target = targetFile(step) ?: return
            JavaStepDefinitionCreator().createStepDefinition(step, target, false)
        }
    }
}
