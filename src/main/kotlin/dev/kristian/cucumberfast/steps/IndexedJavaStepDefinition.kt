package dev.kristian.cucumberfast.steps

import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.util.PsiTreeUtil
import dev.kristian.cucumberfast.expression.StepPattern
import dev.kristian.cucumberfast.index.StepDefinitionEntry
import dev.kristian.cucumberfast.index.StepDefinitionKind
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

    /**
     * Resolved lazily: a pattern naming a project-defined `@ParameterType` needs the project's
     * declarations, which are not available while indexing.
     */
    val pattern: StepPattern by lazy(LazyThreadSafetyMode.PUBLICATION) {
        CucumberParameterTypes.getInstance(containingFile.project).patternFor(entry.expression)
    }

    /** `Given`, `Angenommen`, … — the keyword this definition was declared with. */
    val keyword: String get() = entry.keyword

    private var elementPointer: SmartPsiElementPointer<PsiElement>? = null
    private var resolveFailed = false

    override fun matches(stepName: String): Boolean = pattern.matches(stepName)

    /** The pattern as written — a Cucumber expression stays one, for display and completion. */
    override fun getExpression(): String = entry.expression

    /**
     * The pattern as a *regular expression*, which is what this contract means: the Gherkin
     * plugin compiles it to colour step parameters and lexes it when renaming a step. Returning the
     * raw Cucumber expression makes `{int}` read as a quantifier and throws.
     */
    override fun getCucumberRegex(): String = pattern.regexText

    override fun getCucumberRegexFromElement(element: PsiElement?): String = pattern.regexText

    /**
     * Rewrites the pattern in the source, which is how renaming a step updates the step definition
     * it belongs to. Without this the rename would change every feature file and leave the
     * annotation behind, quietly undefining each step it just renamed.
     */
    override fun setCucumberRegex(newValue: String) {
        val literal = patternLiteral() ?: return
        // Written through verbatim. Renaming a step is a regex-shaped operation in this IDE — the
        // dialog shows the pattern as a regex and locks its special symbols — so a definition
        // originally written as a Cucumber expression comes back as the equivalent regex. That is
        // what Cucumber for Java does too, and rewriting it back into expression form would have to
        // guess which captures were once `{int}`.
        val factory = JavaPsiFacade.getElementFactory(containingFile.project)
        val replacement = factory.createExpressionFromText(
            "\"" + StringUtil.escapeStringCharacters(newValue) + "\"",
            literal,
        )
        literal.replace(replacement)
    }

    /** The string literal holding the pattern, for both annotation and lambda step definitions. */
    private fun patternLiteral(): PsiLiteralExpression? {
        if (!containingFile.isValid || entry.offset >= containingFile.textLength) return null
        val leaf = containingFile.findElementAt(entry.offset) ?: return null
        return when (entry.kind) {
            StepDefinitionKind.ANNOTATION ->
                PsiTreeUtil.getParentOfType(leaf, PsiAnnotation::class.java)
                    ?.findAttributeValue("value") as? PsiLiteralExpression

            StepDefinitionKind.LAMBDA ->
                PsiTreeUtil.getParentOfType(leaf, PsiMethodCallExpression::class.java)
                    ?.argumentList?.expressions?.firstOrNull() as? PsiLiteralExpression
        }
    }

    override fun getVariableNames(): List<String> =
        (getElement() as? PsiMethod)?.parameterList?.parameters?.map { it.name } ?: emptyList()

    override fun getElement(): PsiElement? {
        elementPointer?.let { return it.element }
        if (resolveFailed) return null

        val element = resolveElement()
        if (element == null) {
            resolveFailed = true
            return null
        }
        elementPointer = SmartPointerManager.getInstance(containingFile.project).createSmartPsiElementPointer(element)
        return element
    }

    private fun resolveElement(): PsiElement? {
        if (!containingFile.isValid || entry.offset >= containingFile.textLength) return null
        val leaf = containingFile.findElementAt(entry.offset) ?: return null
        return when (entry.kind) {
            StepDefinitionKind.ANNOTATION -> {
                val annotation = PsiTreeUtil.getParentOfType(leaf, PsiAnnotation::class.java) ?: return null
                if (!isStepAnnotation(annotation)) return null
                PsiTreeUtil.getParentOfType(annotation, PsiMethod::class.java)
            }

            // A lambda step definition has no method of its own; the call registering it is what
            // the user wants to land on.
            StepDefinitionKind.LAMBDA -> PsiTreeUtil.getParentOfType(leaf, PsiMethodCallExpression::class.java)
        }
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
