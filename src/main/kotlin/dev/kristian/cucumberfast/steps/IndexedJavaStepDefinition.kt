package dev.kristian.cucumberfast.steps

import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.util.PsiTreeUtil
import dev.kristian.cucumberfast.expression.StepPattern
import dev.kristian.cucumberfast.index.StepDefinitionEntry
import org.jetbrains.plugins.cucumber.steps.AbstractStepDefinition

/**
 * A step definition backed by the file index rather than by PSI.
 *
 * IntelliJ asks every step definition in the project whether it matches a step, so the cost that
 * matters is the cost of a *non*-matching definition. Everything needed to answer that — the
 * pattern and its literal prefix — comes from the index, so a miss never touches the AST of the
 * file it came from. Only [getElement], called once a definition has actually matched, resolves
 * PSI and confirms that the annotation really is Cucumber's.
 */
class IndexedJavaStepDefinition(
    val containingFile: PsiFile,
    private val entry: StepDefinitionEntry,
) : AbstractStepDefinition(containingFile) {

    val pattern: StepPattern = StepPattern.compile(entry.expression)

    private var methodPointer: SmartPsiElementPointer<PsiMethod>? = null
    private var resolveFailed = false

    override fun matches(stepName: String): Boolean = pattern.matches(stepName)

    override fun getExpression(): String = entry.expression

    override fun getCucumberRegex(): String = entry.expression

    override fun getCucumberRegexFromElement(element: PsiElement?): String = entry.expression

    override fun getVariableNames(): List<String> =
        method()?.parameterList?.parameters?.map { it.name } ?: emptyList()

    override fun getElement(): PsiElement? = method()

    private fun method(): PsiMethod? {
        methodPointer?.let { return it.element }
        if (resolveFailed) return null

        val method = resolveMethod()
        if (method == null) {
            resolveFailed = true
            return null
        }
        methodPointer = SmartPointerManager.getInstance(containingFile.project).createSmartPsiElementPointer(method)
        return method
    }

    private fun resolveMethod(): PsiMethod? {
        if (!containingFile.isValid || entry.annotationOffset >= containingFile.textLength) return null
        val leaf = containingFile.findElementAt(entry.annotationOffset) ?: return null
        val annotation = PsiTreeUtil.getParentOfType(leaf, PsiAnnotation::class.java) ?: return null
        if (!isStepAnnotation(annotation)) return null
        return PsiTreeUtil.getParentOfType(annotation, PsiMethod::class.java)
    }

    /**
     * The index matches annotations by simple name, so `@Given` from an unrelated library would
     * also land there. When the annotation resolves, require it to come from Cucumber; when it does
     * not resolve at all — a project without the dependency configured — stay permissive, since
     * refusing to navigate is worse than navigating to a plausible method.
     */
    private fun isStepAnnotation(annotation: PsiAnnotation): Boolean {
        val target = annotation.nameReferenceElement?.resolve() as? PsiClass ?: return true
        val qualifiedName = target.qualifiedName ?: return true
        return CUCUMBER_ANNOTATION_PACKAGES.any { qualifiedName.startsWith(it) }
    }

    override fun equals(other: Any?): Boolean =
        other is IndexedJavaStepDefinition &&
            other.entry == entry &&
            other.containingFile == containingFile

    override fun hashCode(): Int = 31 * containingFile.hashCode() + entry.hashCode()

    companion object {
        private val CUCUMBER_ANNOTATION_PACKAGES = listOf("io.cucumber.java", "cucumber.api.java")
    }
}
