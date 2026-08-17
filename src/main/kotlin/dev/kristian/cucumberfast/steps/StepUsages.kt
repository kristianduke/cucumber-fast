package dev.kristian.cucumberfast.steps

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
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
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.indexing.FileBasedIndex
import dev.kristian.cucumberfast.index.GherkinStepEntry
import dev.kristian.cucumberfast.index.GherkinStepIndex
import dev.kristian.cucumberfast.index.JavaStepDefinitionScanner
import org.jetbrains.plugins.cucumber.psi.GherkinStep

/**
 * What a Java method is, and which Gherkin steps it implements.
 *
 * The gutter marker, the unused-definition inspection and the code vision hint all want this about
 * the same method during one highlighting pass, so it is computed once per method and cached until
 * either the feature files or the method's own file change. Working it out means resolving the
 * method's annotations, which is why answering "is this even a step definition?" separately would
 * pay that cost again for every method in the file, annotated or not.
 */
object StepUsages {

    private val KEY = Key.create<CachedValue<Info>>("cucumberfast.step.usages")

    private val EMPTY = Info(isStepDefinition = false, hasReadablePattern = false, usages = emptyList())

    /** A feature step a definition matches, kept as file plus offset so no Gherkin PSI is built. */
    data class Usage(val file: VirtualFile, val step: GherkinStepEntry)

    class Info internal constructor(
        /** True when the method carries a Cucumber step annotation, in any language. */
        val isStepDefinition: Boolean,
        /**
         * False when the annotation's pattern could not be read — `@Given(SOME_CONSTANT)` rather
         * than a string literal. Such a definition cannot be matched against anything, so it must
         * not be reported as unused.
         */
        val hasReadablePattern: Boolean,
        val usages: List<Usage>,
    )

    fun of(method: PsiMethod): List<Usage> = info(method).usages

    fun isStepDefinition(method: PsiMethod): Boolean = info(method).isStepDefinition

    fun info(method: PsiMethod): Info {
        val project = method.project
        if (DumbService.isDumb(project)) return EMPTY
        return CachedValuesManager.getManager(project).getCachedValue(method, KEY, {
            CachedValueProvider.Result.create(
                compute(method),
                gherkinIndexTracker(project),
                method.containingFile,
            )
        }, false)
    }

    private fun compute(method: PsiMethod): Info {
        val annotations = method.annotations.filter { isStepAnnotation(it) }
        if (annotations.isEmpty()) return EMPTY

        val parameterTypes = CucumberParameterTypes.getInstance(method.project)
        val patterns = annotations.mapNotNull { annotation ->
            val expression = (annotation.findAttributeValue("value") as? PsiLiteralExpression)?.value as? String
            expression?.let(parameterTypes::patternFor)
        }
        if (patterns.isEmpty()) {
            return Info(isStepDefinition = true, hasReadablePattern = false, usages = emptyList())
        }

        val scope = GlobalSearchScope.projectScope(method.project)
        val usages = patterns
            .flatMap { StepSearch.featureStepsFor(method.project, scope, it) }
            .map { (file, step) -> Usage(file, step) }
            .distinctBy { it.file to it.step.offset }
        return Info(isStepDefinition = true, hasReadablePattern = true, usages = usages)
    }

    /** Materialises the Gherkin PSI for [usages]; called on click, not while highlighting. */
    fun resolve(project: Project, usages: List<Usage>): List<PsiElement> {
        val psiManager = PsiManager.getInstance(project)
        return usages.mapNotNull { usage ->
            val psiFile = psiManager.findFile(usage.file) ?: return@mapNotNull null
            PsiTreeUtil.getParentOfType(psiFile.findElementAt(usage.step.offset), GherkinStep::class.java)
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

    private fun gherkinIndexTracker(project: Project): ModificationTracker =
        ModificationTracker {
            FileBasedIndex.getInstance().getIndexModificationStamp(GherkinStepIndex.NAME, project)
        }

    private val CUCUMBER_STEP_PACKAGES = listOf("io.cucumber.java.", "cucumber.api.java.")

}
