package dev.kristian.cucumberfast.navigation

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.util.PsiTreeUtil
import dev.kristian.cucumberfast.steps.StepUsages
import org.jetbrains.plugins.cucumber.psi.GherkinFeature
import org.jetbrains.plugins.cucumber.psi.GherkinStep
import org.jetbrains.plugins.cucumber.psi.GherkinStepsHolder

/**
 * One row of the "which steps does this implement" popup, with everything needed to say *where* the
 * step is and *what* surrounds it.
 *
 * The popup model deliberately holds pointers rather than `PsiElement`s: the platform asserts
 * against PSI in a popup model, and a pointer also survives the file being edited while the popup
 * is open.
 */
class StepUsageItem private constructor(
    private val pointer: SmartPsiElementPointer<GherkinStep>,
    /** `Given I have 42 cukes` — the keyword and text as written. */
    val stepText: String,
    /** `Cukes › Eating cukes` — the feature and scenario the step belongs to. */
    val container: String,
    /** `shopping.feature:12` */
    val location: String,
    /** The whole scenario, for the preview pane. */
    val scenarioLines: List<String>,
    /** Index into [scenarioLines] of the step itself, so the preview can point at it. */
    val stepLineIndex: Int,
) {

    val element: GherkinStep? get() = pointer.element

    /** What the popup's speed search matches against. */
    val filterText: String get() = "$stepText $container"

    companion object {
        /** How much of a scenario the preview shows before it is worth truncating. */
        private const val MAX_PREVIEW_LINES = 14

        fun create(project: Project, usages: List<StepUsages.Usage>): List<StepUsageItem> =
            StepUsages.resolve(project, usages)
                .filterIsInstance<GherkinStep>()
                .map { create(it) }

        private fun create(step: GherkinStep): StepUsageItem {
            val file = step.containingFile
            val fileText = file.text
            val stepLine = StringUtil.offsetToLineNumber(fileText, step.textOffset)

            val scenario = PsiTreeUtil.getParentOfType(step, GherkinStepsHolder::class.java)
            val feature = PsiTreeUtil.getParentOfType(step, GherkinFeature::class.java)

            val firstLine = scenario?.let { StringUtil.offsetToLineNumber(fileText, it.textRange.startOffset) } ?: stepLine
            val lastLine = scenario?.let { StringUtil.offsetToLineNumber(fileText, it.textRange.endOffset) } ?: stepLine

            return StepUsageItem(
                pointer = SmartPointerManager.getInstance(step.project).createSmartPsiElementPointer(step),
                stepText = step.text.lineSequence().first().trim(),
                container = containerText(feature, scenario),
                location = "${file.name}:${stepLine + 1}",
                scenarioLines = previewLines(fileText, firstLine, lastLine),
                stepLineIndex = stepLine - firstLine,
            )
        }

        /**
         * The scenario as it appears in the file, dedented as a block. Taking the PSI element's own
         * text instead would drop the indentation of its first line only, leaving every other line
         * hanging several columns to the right.
         */
        private fun previewLines(fileText: String, firstLine: Int, lastLine: Int): List<String> {
            val lines = fileText.lines()
            if (firstLine !in lines.indices) return emptyList()
            val block = lines.subList(firstLine, minOf(lastLine, lines.size - 1) + 1).dropLastWhile { it.isBlank() }
            val indent = block.filter { it.isNotBlank() }
                .minOfOrNull { line -> line.takeWhile(Char::isWhitespace).length } ?: 0
            val dedented = block.map { if (it.isBlank()) "" else it.substring(indent) }
            return if (dedented.size > MAX_PREVIEW_LINES) dedented.take(MAX_PREVIEW_LINES) + "…" else dedented
        }

        private fun containerText(feature: GherkinFeature?, scenario: GherkinStepsHolder?): String {
            val featureName = feature?.featureName?.trim().orEmpty()
            val scenarioName = scenario?.scenarioName?.trim()
                ?.ifEmpty { scenario.scenarioKeyword?.trim() }
                .orEmpty()
            return listOf(featureName, scenarioName).filter { it.isNotEmpty() }.joinToString(" › ")
        }
    }
}
