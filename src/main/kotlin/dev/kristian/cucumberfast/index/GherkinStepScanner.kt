package dev.kristian.cucumberfast.index

/**
 * Finds the steps in a `.feature` file by scanning lines, without building Gherkin PSI.
 *
 * Only English keywords are recognised for now; a localized feature file (`# language: de`) simply
 * contributes no steps to the index, which costs reverse navigation for that file but never
 * produces a wrong link.
 */
object GherkinStepScanner {

    private val STEP_KEYWORDS = listOf("Given", "When", "Then", "And", "But", "*")

    fun scan(text: CharSequence): List<GherkinStepEntry> {
        val result = ArrayList<GherkinStepEntry>()
        var lineStart = 0
        var inDocString = false

        while (lineStart <= text.length) {
            var lineEnd = lineStart
            while (lineEnd < text.length && text[lineEnd] != '\n') lineEnd++

            var contentStart = lineStart
            while (contentStart < lineEnd && text[contentStart].isWhitespace()) contentStart++
            var contentEnd = lineEnd
            while (contentEnd > contentStart && text[contentEnd - 1].isWhitespace()) contentEnd--

            val line = text.subSequence(contentStart, contentEnd).toString()
            when {
                line.startsWith("\"\"\"") || line.startsWith("```") -> inDocString = !inDocString
                inDocString || line.startsWith("#") -> Unit
                else -> stepText(line)?.let { (keywordLength, stepText) ->
                    result.add(GherkinStepEntry(stepText, contentStart + keywordLength))
                }
            }

            lineStart = lineEnd + 1
        }
        return result
    }

    /** Returns the offset of the step text within [line] and the text itself, or null. */
    private fun stepText(line: String): Pair<Int, String>? {
        for (keyword in STEP_KEYWORDS) {
            if (!line.startsWith(keyword)) continue
            var i = keyword.length
            if (i >= line.length || !line[i].isWhitespace()) continue
            while (i < line.length && line[i].isWhitespace()) i++
            if (i >= line.length) return null
            return i to line.substring(i)
        }
        return null
    }
}
