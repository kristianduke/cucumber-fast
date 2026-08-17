package dev.kristian.cucumberfast.annotator

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import dev.kristian.cucumberfast.reference.FastCucumberStepReference
import org.jetbrains.plugins.cucumber.psi.GherkinHighlighter
import org.jetbrains.plugins.cucumber.psi.GherkinPsiUtil
import org.jetbrains.plugins.cucumber.psi.GherkinStep

/**
 * Colours the parts of a step that its definition captures as parameters — the `42` in
 * `Given I have 42 cukes`.
 *
 * The Gherkin plugin's annotator does this too, but it resolves the step through the extension
 * point, which this plugin leaves empty to keep resolution off the linear path. Same highlighting,
 * driven by the indexed lookup.
 */
class StepParameterAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is GherkinStep) return
        if (DumbService.isDumb(element.project)) return

        val reference = element.references.filterIsInstance<FastCucumberStepReference>().firstOrNull() ?: return
        val definition = reference.resolveToDefinitions().firstOrNull() ?: return

        val ranges = GherkinPsiUtil.buildParameterRanges(element, definition, reference.rangeInElement.startOffset)
            ?: return

        val stepStart = element.textRange.startOffset
        for (range in ranges) {
            if (range.isEmpty) continue
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                .range(range.shiftRight(stepStart))
                .textAttributes(GherkinHighlighter.REGEXP_PARAMETER)
                .create()
        }
    }
}
