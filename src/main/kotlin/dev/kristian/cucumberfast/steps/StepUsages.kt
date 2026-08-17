package dev.kristian.cucumberfast.steps

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.util.indexing.FileBasedIndex
import dev.kristian.cucumberfast.expression.StepPattern
import dev.kristian.cucumberfast.index.GherkinStepEntry
import dev.kristian.cucumberfast.index.GherkinStepIndex
import dev.kristian.cucumberfast.index.JavaStepDefinitionScanner
import org.jetbrains.plugins.cucumber.psi.GherkinStep

/**
 * Which Gherkin steps a step definition method implements.
 *
 * The gutter marker, the unused-definition inspection and the code vision hint all want this same
 * answer for the same method during one highlighting pass, so it is computed once and cached until
 * either the feature files or the method's own file change.
 */
object StepUsages {

    private val KEY = Key.create<CachedValue<List<Usage>>>("cucumberfast.step.usages")

    /** A feature step a definition matches, kept as file plus offset so no Gherkin PSI is built. */
    data class Usage(val file: VirtualFile, val step: GherkinStepEntry)

    /** Empty when the method is not a step definition at all, so callers can skip it cheaply. */
    fun of(method: PsiMethod): List<Usage> {
        val project = method.project
        if (DumbService.isDumb(project)) return emptyList()
        return CachedValuesManager.getManager(project).getCachedValue(method, KEY, {
            val patterns = stepPatternsOf(method)
            val usages = if (patterns.isEmpty()) {
                emptyList()
            } else {
                val scope = GlobalSearchScope.projectScope(project)
                patterns.flatMap { StepSearch.featureStepsFor(project, scope, it) }
                    .map { (file, step) -> Usage(file, step) }
                    .distinctBy { it.file to it.step.offset }
            }
            CachedValueProvider.Result.create(usages, gherkinIndexTracker(project), method.containingFile)
        }, false)
    }

    /** True when this method carries a Cucumber step annotation, whatever language it is written in. */
    fun isStepDefinition(method: PsiMethod): Boolean = method.annotations.any { isStepAnnotation(it) }

    /** Materialises the Gherkin PSI for [usages]; called on click, not while highlighting. */
    fun resolve(project: com.intellij.openapi.project.Project, usages: List<Usage>): List<PsiElement> {
        val psiManager = PsiManager.getInstance(project)
        return usages.mapNotNull { usage ->
            val psiFile = psiManager.findFile(usage.file) ?: return@mapNotNull null
            com.intellij.psi.util.PsiTreeUtil.getParentOfType(
                psiFile.findElementAt(usage.step.offset),
                GherkinStep::class.java,
            )
        }
    }

    private fun stepPatternsOf(method: PsiMethod): List<StepPattern> {
        val parameterTypes = CucumberParameterTypes.getInstance(method.project)
        return method.annotations.mapNotNull { annotation ->
            if (!isStepAnnotation(annotation)) return@mapNotNull null
            val expression = (annotation.findAttributeValue("value") as? PsiLiteralExpression)?.value as? String
                ?: return@mapNotNull null
            parameterTypes.patternFor(expression)
        }
    }

    /**
     * Recognised either by package — which covers every localized annotation — or, for a project
     * without the dependency on its classpath, by the English simple names.
     */
    private fun isStepAnnotation(annotation: PsiAnnotation): Boolean {
        val resolved = annotation.nameReferenceElement?.resolve() as? PsiClass
        val qualifiedName = resolved?.qualifiedName
        if (qualifiedName != null) {
            return CUCUMBER_STEP_PACKAGES.any { qualifiedName.startsWith(it) }
        }
        return annotation.nameReferenceElement?.referenceName in JavaStepDefinitionScanner.ENGLISH_STEP_KEYWORDS
    }

    private fun gherkinIndexTracker(project: com.intellij.openapi.project.Project): ModificationTracker =
        ModificationTracker {
            FileBasedIndex.getInstance().getIndexModificationStamp(GherkinStepIndex.NAME, project)
        }

    private val CUCUMBER_STEP_PACKAGES = listOf("io.cucumber.java.", "cucumber.api.java.")
}
