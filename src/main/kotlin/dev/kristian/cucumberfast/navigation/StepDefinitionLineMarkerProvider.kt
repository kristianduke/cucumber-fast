package dev.kristian.cucumberfast.navigation

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import dev.kristian.cucumberfast.expression.StepPattern
import dev.kristian.cucumberfast.index.JavaStepDefinitionScanner
import dev.kristian.cucumberfast.steps.StepSearch
import icons.CucumberIcons
import org.jetbrains.plugins.cucumber.psi.GherkinStep
import javax.swing.Icon

/**
 * Gutter icon on a step definition method leading to the Gherkin steps it implements.
 *
 * Deciding whether to show the icon uses index data only — the step texts, not their PSI. Feature
 * files are parsed lazily, when the popup is opened, so a method with a hundred call sites costs
 * the same to highlight as one with none.
 */
class StepDefinitionLineMarkerProvider : RelatedItemLineMarkerProvider() {

    override fun getName(): String = "Cucumber step usages"

    override fun getIcon(): Icon = CucumberIcons.Cucumber

    override fun collectNavigationMarkers(
        element: PsiElement,
        result: MutableCollection<in RelatedItemLineMarkerInfo<*>>,
    ) {
        if (element !is PsiIdentifier) return
        val method = element.parent as? PsiMethod ?: return
        if (method.nameIdentifier !== element) return

        val project = element.project
        if (DumbService.isDumb(project)) return

        val patterns = method.annotations.mapNotNull(::stepPatternOf)
        if (patterns.isEmpty()) return

        val scope = GlobalSearchScope.projectScope(project)
        val steps = patterns.flatMap { StepSearch.featureStepsFor(project, scope, it) }
        if (steps.isEmpty()) return

        val targets: NotNullLazyValue<Collection<PsiElement>> = NotNullLazyValue.lazy {
            val psiManager = PsiManager.getInstance(project)
            steps.mapNotNull { (file, entry) ->
                val psiFile = psiManager.findFile(file) ?: return@mapNotNull null
                PsiTreeUtil.getParentOfType(psiFile.findElementAt(entry.offset), GherkinStep::class.java)
            }
        }

        result.add(
            NavigationGutterIconBuilder.create(CucumberIcons.Cucumber)
                .setTargets(targets)
                .setTooltipText(if (steps.size == 1) "Implements 1 Gherkin step" else "Implements ${steps.size} Gherkin steps")
                .setPopupTitle("Gherkin Steps")
                .setEmptyPopupText("No Gherkin steps found")
                .createLineMarkerInfo(element)
        )
    }

    private fun stepPatternOf(annotation: PsiAnnotation): StepPattern? {
        val name = annotation.nameReferenceElement?.referenceName ?: return null
        if (name !in JavaStepDefinitionScanner.STEP_ANNOTATIONS) return null
        val expression = (annotation.findAttributeValue("value") as? PsiLiteralExpression)?.value as? String ?: return null
        return StepPattern.compile(expression)
    }
}
