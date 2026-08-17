package dev.kristian.cucumberfast.navigation

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.pom.Navigatable
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.kristian.cucumberfast.steps.StepUsages
import icons.CucumberIcons
import java.awt.BorderLayout
import java.awt.Component
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JList
import javax.swing.ListCellRenderer

/**
 * The popup listing the Gherkin steps a step definition implements, shared by the gutter icon and
 * the code vision hint so both look and behave the same.
 *
 * A bare list of step texts does not say much — the same sentence appears in many scenarios — so
 * each row carries the feature and scenario it belongs to, and moving over a row previews that
 * whole scenario underneath, with the step in question marked.
 */
object StepUsagePopup {

    private const val TITLE = "Gherkin Steps"

    fun show(project: Project, usages: List<StepUsages.Usage>, editor: Editor?, event: MouseEvent?) {
        val items = StepUsageItem.create(project, usages)
        when (items.size) {
            0 -> return
            1 -> {
                navigate(items.single())
                return
            }
        }

        val preview = ScenarioPreview()
        preview.show(items.first())

        val popup = JBPopupFactory.getInstance()
            .createPopupChooserBuilder(items)
            .setTitle(TITLE)
            .setRenderer(StepUsageRenderer())
            .setNamerForFiltering { it.filterText }
            .setAutoselectOnMouseMove(true)
            .setItemSelectedCallback { item -> item?.let(preview::show) }
            .setItemChosenCallback(::navigate)
            .setAdvertiser(preview)
            .setResizable(true)
            .createPopup()

        when {
            event != null -> popup.show(RelativePoint(event))
            editor != null -> popup.showInBestPositionFor(editor)
            else -> popup.showCenteredInCurrentWindow(project)
        }
    }

    private fun navigate(item: StepUsageItem) {
        (item.element as? Navigatable)?.takeIf { it.canNavigate() }?.navigate(true)
    }

    /** Two lines per row: the step itself, then the feature and scenario it sits in. */
    private class StepUsageRenderer : ListCellRenderer<StepUsageItem> {

        private val stepLabel = JBLabel().apply { icon = CucumberIcons.Cucumber }
        private val contextLabel = JBLabel().apply {
            font = JBUI.Fonts.smallFont()
            border = JBUI.Borders.emptyLeft(20)
        }
        private val panel = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(4, 8)
            add(stepLabel)
            add(contextLabel)
        }

        override fun getListCellRendererComponent(
            list: JList<out StepUsageItem>,
            value: StepUsageItem?,
            index: Int,
            selected: Boolean,
            focused: Boolean,
        ): Component {
            val background = if (selected) list.selectionBackground else list.background
            val foreground = if (selected) list.selectionForeground else list.foreground
            panel.background = background
            stepLabel.background = background
            contextLabel.background = background
            stepLabel.foreground = foreground
            contextLabel.foreground = if (selected) foreground else UIUtil.getContextHelpForeground()

            stepLabel.text = value?.stepText.orEmpty()
            contextLabel.text = listOfNotNull(
                value?.container?.takeIf { it.isNotEmpty() },
                value?.location,
            ).joinToString("  —  ")
            return panel
        }
    }

    /** The scenario around the selected step, rendered as a code snippet under the list. */
    private class ScenarioPreview : JBPanel<ScenarioPreview>(BorderLayout()) {

        private val label = JBLabel().apply {
            font = JBUI.Fonts.create(com.intellij.util.ui.JBFont.MONOSPACED, JBUI.Fonts.label().size)
            verticalAlignment = JBLabel.TOP
        }

        init {
            border = BorderFactory.createCompoundBorder(
                JBUI.Borders.customLineTop(UIUtil.getBoundsColor()),
                JBUI.Borders.empty(6, 8),
            )
            add(label, BorderLayout.CENTER)
        }

        fun show(item: StepUsageItem) {
            label.text = buildString {
                append("<html><body>")
                item.scenarioLines.forEachIndexed { index, line ->
                    val escaped = StringUtil.escapeXmlEntities(line.ifEmpty { " " })
                        .replace(" ", "&nbsp;")
                    if (index == item.stepLineIndex) {
                        append("<b>").append(escaped).append("</b>")
                    } else {
                        append(escaped)
                    }
                    append("<br>")
                }
                append("</body></html>")
            }
        }
    }
}
