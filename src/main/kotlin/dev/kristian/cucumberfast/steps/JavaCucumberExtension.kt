package dev.kristian.cucumberfast.steps

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.jetbrains.plugins.cucumber.BDDFrameworkType
import org.jetbrains.plugins.cucumber.StepDefinitionCreator
import org.jetbrains.plugins.cucumber.psi.GherkinFile
import org.jetbrains.plugins.cucumber.steps.AbstractCucumberExtension
import org.jetbrains.plugins.cucumber.steps.AbstractStepDefinition

/**
 * Registers Java as a step definition language with the Gherkin plugin, without routing resolution
 * through it.
 *
 * The extension point's contract is to hand back *every* step definition in the module and let the
 * caller filter them, which is a linear pass per step — and the Gherkin plugin runs it from three
 * places on every highlighting pass: its reference, its undefined-step inspection, and its
 * annotator's parameter highlighting. This plugin resolves steps through the bucketed index
 * instead, so [loadStepsFor] and [getStepName] deliberately return nothing and the callers above
 * short-circuit at no cost.
 *
 * The extension stays registered because other behaviour hangs off it that has nothing to do with
 * resolution — notably [isGherkin6Supported], which is what allows `Rule:` to parse at all.
 */
class JavaCucumberExtension : AbstractCucumberExtension() {

    override fun isStepLikeFile(child: PsiElement, parent: PsiElement): Boolean = child is PsiClassOwner

    override fun isWritableStepLikeFile(child: PsiElement, parent: PsiElement): Boolean =
        child is PsiClassOwner && child.isWritable

    override fun getStepFileType(): BDDFrameworkType = BDDFrameworkType(JavaFileType.INSTANCE)

    override fun getStepDefinitionCreator(): StepDefinitionCreator = JavaStepDefinitionCreator()

    /**
     * Enables Gherkin 6 syntax (`Rule:`) unconditionally rather than probing the module's Cucumber
     * version. On a pre-6 runtime this only means the IDE parses a keyword the runtime would
     * reject — it never changes what a step resolves to.
     */
    override fun isGherkin6Supported(module: Module): Boolean = true

    /**
     * Empty by design: see the class comment. Resolution goes through
     * `dev.kristian.cucumberfast.reference.FastCucumberStepReference`.
     */
    override fun loadStepsFor(featureFile: PsiFile?, module: Module): List<AbstractStepDefinition> = emptyList()

    /**
     * Null makes `CucumberStepReference.multiResolveInner` return before it loads anything, which is
     * the cheapest way to keep the Gherkin plugin's own reference from doing the work twice.
     */
    override fun getStepName(step: PsiElement): String? = null

    override fun getStepDefinitionContainers(featureFile: GherkinFile): Collection<PsiFile> {
        val module = ModuleUtilCore.findModuleForPsiElement(featureFile) ?: return emptyList()
        return StepSearch.definitionContainers(module)
    }
}
