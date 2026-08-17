package dev.kristian.cucumberfast.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.DumbService
import com.intellij.psi.util.PsiTreeUtil
import dev.kristian.cucumberfast.steps.StepSearch
import icons.CucumberIcons
import org.jetbrains.plugins.cucumber.psi.GherkinFile
import org.jetbrains.plugins.cucumber.psi.GherkinStep

/**
 * Completes step text inside a feature file from the project's step definitions.
 *
 * The Gherkin plugin's own completion reads step definitions through the extension point, which
 * this plugin leaves empty so resolution stays off the linear path — so completion is supplied
 * here instead, from the same indexed definitions.
 */
class StepCompletionContributor : CompletionContributor() {

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val position = parameters.position
        if (position.containingFile !is GherkinFile) return
        val step = PsiTreeUtil.getParentOfType(position, GherkinStep::class.java) ?: return

        val project = position.project
        if (DumbService.isDumb(project)) return
        val module = ModuleUtilCore.findModuleForPsiElement(position) ?: return

        // The default prefix is the word before the caret, so `I have` would offer nothing matching
        // "I have {int} cukes". A step is matched from its start, so the prefix is the whole step
        // text typed so far.
        val prefixed = result.withPrefixMatcher(typedStepText(step, parameters))

        for (definition in StepSearch.allDefinitions(module)) {
            val expression = definition.expression
            if (expression.isNullOrEmpty()) continue
            prefixed.addElement(
                LookupElementBuilder.create(expression)
                    .withIcon(CucumberIcons.Cucumber)
                    .withTypeText(definition.keyword, true),
            )
        }
    }

    /** Everything between the step's keyword and the caret. */
    private fun typedStepText(step: GherkinStep, parameters: CompletionParameters): String {
        val fileText = step.containingFile.text
        var start = step.textRange.startOffset + (step.keyword?.textLength ?: 0)
        while (start < fileText.length && fileText[start].isWhitespace()) start++
        val caret = parameters.offset.coerceIn(start, fileText.length)
        return fileText.substring(start, caret)
    }
}
