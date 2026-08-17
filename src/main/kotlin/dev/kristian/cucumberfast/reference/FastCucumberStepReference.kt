package dev.kristian.cucumberfast.reference

import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.ResolveResult
import com.intellij.psi.impl.source.resolve.ResolveCache
import dev.kristian.cucumberfast.steps.IndexedJavaStepDefinition
import dev.kristian.cucumberfast.steps.StepSearch
import org.jetbrains.plugins.cucumber.psi.GherkinStep
import org.jetbrains.plugins.cucumber.steps.AbstractStepDefinition
import org.jetbrains.plugins.cucumber.steps.reference.CucumberStepReference

/**
 * Resolves a Gherkin step to its Java step definitions through the bucketed index.
 *
 * The Gherkin plugin's own reference asks every step definition in the module whether it matches.
 * This one asks only the definitions whose pattern could start the way the step does — see
 * `StepSearch.definitionsForStep` — which is what keeps resolution flat as a suite grows.
 *
 * It extends that reference rather than replacing it, and is registered at a higher priority so it
 * comes first among the step's references. Several parts of the Gherkin plugin — renaming a step,
 * the scenario-to-outline intention, the annotator that colours step parameters — look up the first
 * `CucumberStepReference` on the element and resolve through it. Being one keeps all of that
 * working, on the fast lookup, instead of quietly breaking when the extension point stopped
 * answering.
 */
class FastCucumberStepReference(
    private val step: GherkinStep,
    range: TextRange,
) : CucumberStepReference(step, range) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        ResolveCache.getInstance(step.project).resolveWithCaching(this, RESOLVER, false, incompleteCode)

    override fun resolveToDefinitions(): Collection<AbstractStepDefinition> = fastDefinitions()

    /** The matching definitions, typed, for callers that want the pattern rather than the method. */
    fun definitions(): List<IndexedJavaStepDefinition> = fastDefinitions()

    private fun fastDefinitions(): List<IndexedJavaStepDefinition> {
        val module = ModuleUtilCore.findModuleForPsiElement(step) ?: return emptyList()
        val stepText = step.substitutedName ?: return emptyList()
        return StepSearch.definitionsForStep(module, stepText)
    }

    private object Resolver : ResolveCache.PolyVariantResolver<FastCucumberStepReference> {
        override fun resolve(reference: FastCucumberStepReference, incompleteCode: Boolean): Array<ResolveResult> {
            val elements = reference.fastDefinitions().mapNotNull { it.element }.distinct()
            return elements.map(::PsiElementResolveResult).toTypedArray()
        }
    }

    companion object {
        private val RESOLVER = Resolver
    }
}

/** The first reference on [step] that resolves through the indexed lookup, if any. */
internal fun GherkinStep.fastStepReference(): FastCucumberStepReference? =
    references.filterIsInstance<FastCucumberStepReference>().firstOrNull()

