package dev.kristian.cucumberfast.navigation

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiMethod
import dev.kristian.cucumberfast.steps.StepUsages
import icons.CucumberIcons
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

        val usages = StepUsages.of(method)
        if (usages.isEmpty()) return

        val targets = NotNullLazyValue.lazy<Collection<PsiElement>> { StepUsages.resolve(project, usages) }

        result.add(
            NavigationGutterIconBuilder.create(CucumberIcons.Cucumber)
                .setTargets(targets)
                .setTooltipText(
                    if (usages.size == 1) "Implements 1 Gherkin step" else "Implements ${usages.size} Gherkin steps",
                )
                .setPopupTitle("Gherkin Steps")
                .setEmptyPopupText("No Gherkin steps found")
                .createLineMarkerInfo(element),
        )
    }
}
