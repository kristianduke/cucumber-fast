package dev.kristian.cucumberfast.reference

import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.ResolveResult
import com.intellij.psi.impl.source.resolve.ResolveCache
import dev.kristian.cucumberfast.steps.IndexedJavaStepDefinition
import dev.kristian.cucumberfast.steps.StepSearch
import org.jetbrains.plugins.cucumber.psi.GherkinStep

/**
 * Resolves a Gherkin step to its Java step definitions through the bucketed index.
 *
 * The Gherkin plugin's own reference asks every step definition in the module whether it matches.
 * This one asks only the definitions whose pattern could start the way the step does — see
 * `StepSearch.definitionsForStep` — which is what keeps resolution flat as a suite grows.
 */
class FastCucumberStepReference(
    private val step: GherkinStep,
    private val range: TextRange,
) : PsiPolyVariantReference {

    override fun getElement(): PsiElement = step

    override fun getRangeInElement(): TextRange = range

    override fun getCanonicalText(): String = step.text

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        ResolveCache.getInstance(step.project).resolveWithCaching(this, RESOLVER, false, incompleteCode)

    override fun resolve(): PsiElement? = multiResolve(false).singleOrNull()?.element

    /** The definitions themselves, for callers that need the pattern rather than the method. */
    fun resolveToDefinitions(): List<IndexedJavaStepDefinition> {
        val module = ModuleUtilCore.findModuleForPsiElement(step) ?: return emptyList()
        val stepText = step.substitutedName ?: return emptyList()
        return StepSearch.definitionsForStep(module, stepText)
    }

    override fun isReferenceTo(element: PsiElement): Boolean =
        multiResolve(false).any { step.manager.areElementsEquivalent(it.element, element) }

    override fun handleElementRename(newElementName: String): PsiElement = step

    override fun bindToElement(element: PsiElement): PsiElement = step

    /**
     * Hard, like the Gherkin plugin's own reference. When several references share a range the
     * platform ranks them before resolving, and a soft one loses to the hard one the Gherkin plugin
     * contributes — which now resolves to nothing, so Ctrl+click would find nothing.
     */
    override fun isSoft(): Boolean = false

    private object Resolver : ResolveCache.PolyVariantResolver<FastCucumberStepReference> {
        override fun resolve(reference: FastCucumberStepReference, incompleteCode: Boolean): Array<ResolveResult> {
            val elements = reference.resolveToDefinitions().mapNotNull { it.element }.distinct()
            return elements.map(::PsiElementResolveResult).toTypedArray()
        }
    }

    companion object {
        private val RESOLVER = Resolver
    }
}
