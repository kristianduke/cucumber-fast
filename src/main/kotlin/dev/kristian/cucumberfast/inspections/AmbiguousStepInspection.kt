package dev.kristian.cucumberfast.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElementVisitor
import dev.kristian.cucumberfast.steps.StepSearch
import org.jetbrains.plugins.cucumber.psi.GherkinElementVisitor
import org.jetbrains.plugins.cucumber.psi.GherkinStep

/**
 * Reports a step that more than one step definition matches.
 *
 * Cucumber fails such a step at runtime with `AmbiguousStepDefinitionsException`, but the IDE
 * hides the problem: `CucumberStepHelper.findStepDefinitions` keeps only the longest matching
 * pattern per framework, so the step looks perfectly resolved right up until the suite runs.
 *
 * The lookup is the bucketed one — the step's own two keys plus the catch-all — so only the
 * definitions that could plausibly match are considered, and PSI is resolved only for those that
 * actually do.
 */
class AmbiguousStepInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : GherkinElementVisitor() {
            override fun visitStep(step: GherkinStep) {
                val project = step.project
                if (DumbService.isDumb(project)) return

                val stepText = step.substitutedName ?: return
                val module = ModuleUtilCore.findModuleForPsiElement(step) ?: return

                val matching = StepSearch.definitionsForStep(module, stepText)
                if (matching.size < 2) return

                // Two entries pointing at the same method are one definition, not an ambiguity.
                val distinctTargets = matching.mapNotNull { it.element }.distinct()
                if (distinctTargets.size < 2) return

                holder.registerProblem(
                    step,
                    "Step is matched by ${distinctTargets.size} step definitions; Cucumber fails on ambiguous steps",
                )
            }
        }
}
