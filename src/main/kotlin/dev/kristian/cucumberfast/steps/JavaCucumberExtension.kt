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
 * Plugs Java step definitions into the Gherkin plugin.
 *
 * Registering here rather than contributing a separate reference is deliberate: the Gherkin
 * plugin's own reference, undefined-step inspection, completion, rename and "go to related" all
 * resolve through this extension point, so they all pick up the indexed implementation at once.
 */
class JavaCucumberExtension : AbstractCucumberExtension() {

    override fun isStepLikeFile(child: PsiElement, parent: PsiElement): Boolean = child is PsiClassOwner

    override fun isWritableStepLikeFile(child: PsiElement, parent: PsiElement): Boolean =
        child is PsiClassOwner && child.isWritable

    override fun getStepFileType(): BDDFrameworkType = BDDFrameworkType(JavaFileType.INSTANCE)

    /**
     * Enables Gherkin 6 syntax (`Rule:`) unconditionally rather than probing the module's Cucumber
     * version. On a pre-6 runtime this only means the IDE parses a keyword the runtime would
     * reject — it never changes what a step resolves to.
     */
    override fun isGherkin6Supported(module: Module): Boolean = true

    override fun getStepDefinitionCreator(): StepDefinitionCreator = JavaStepDefinitionCreator()

    override fun loadStepsFor(featureFile: PsiFile?, module: Module): List<AbstractStepDefinition> =
        StepSearch.allDefinitions(module)

    override fun getStepDefinitionContainers(featureFile: GherkinFile): Collection<PsiFile> {
        val module = ModuleUtilCore.findModuleForPsiElement(featureFile) ?: return emptyList()
        return StepSearch.definitionContainers(module)
    }
}
