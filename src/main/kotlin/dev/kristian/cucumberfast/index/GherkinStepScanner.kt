package dev.kristian.cucumberfast.index

import org.jetbrains.plugins.cucumber.psi.GherkinKeywordProvider
import org.jetbrains.plugins.cucumber.psi.i18n.JsonGherkinKeywordProvider

/**
 * Finds the steps in a `.feature` file by scanning lines, without building Gherkin PSI.
 *
 * Keywords come from the Gherkin plugin's own i18n table, selected by the file's
 * `# language:` header, so a localized feature file indexes as well as an English one. Languages
 * that do not separate the keyword from the step with a space (Chinese, Japanese) are handled by
 * asking that same table rather than assuming.
 */
object GherkinStepScanner {

    private const val DEFAULT_LANGUAGE = "en"

    private val keywordProvider: GherkinKeywordProvider by lazy { JsonGherkinKeywordProvider.getKeywordProvider() }

    fun scan(text: CharSequence): List<GherkinStepEntry> {
        // An unrecognised `# language:` header must not silently index nothing.
        val declared = languageOf(text)
        val declaredKeywords = stepKeywords(declared)
        val language = if (declaredKeywords.isEmpty()) DEFAULT_LANGUAGE else declared
        val keywords = declaredKeywords.ifEmpty { stepKeywords(DEFAULT_LANGUAGE).ifEmpty { ENGLISH_FALLBACK } }

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
                else -> stepText(line, keywords, language)?.let { (keywordLength, stepText) ->
                    result.add(GherkinStepEntry(stepText, contentStart + keywordLength))
                }
            }

            lineStart = lineEnd + 1
        }
        return result
    }

    /** The `# language: de` header, if the file has one. */
    private fun languageOf(text: CharSequence): String {
        var lineStart = 0
        // The header must precede the feature, so only the leading comment block is worth reading.
        while (lineStart < text.length) {
            var lineEnd = lineStart
            while (lineEnd < text.length && text[lineEnd] != '\n') lineEnd++
            val line = text.subSequence(lineStart, lineEnd).toString().trim()
            if (line.isNotEmpty()) {
                if (!line.startsWith("#")) return DEFAULT_LANGUAGE
                val marker = line.removePrefix("#").trim()
                if (marker.startsWith("language:", ignoreCase = true)) {
                    return marker.substringAfter(':').trim().ifEmpty { DEFAULT_LANGUAGE }
                }
            }
            lineStart = lineEnd + 1
        }
        return DEFAULT_LANGUAGE
    }

    private val ENGLISH_FALLBACK = listOf("Given", "When", "Then", "And", "But", "*")

    private fun stepKeywords(language: String): List<String> {
        val table = try {
            keywordProvider.getKeywordsTable(language)
        } catch (_: RuntimeException) {
            null
        } ?: return emptyList()
        // Longest first, so a short keyword never shadows a longer one starting with it.
        return table.stepKeywords.map { it.trim() }.filter { it.isNotEmpty() }.sortedByDescending { it.length }
    }

    /** Returns the offset of the step text within [line] and the text itself, or null. */
    private fun stepText(line: String, keywords: List<String>, language: String): Pair<Int, String>? {
        for (keyword in keywords) {
            if (!line.startsWith(keyword)) continue
            var i = keyword.length
            if (isSpaceRequired(language, keyword)) {
                if (i >= line.length || !line[i].isWhitespace()) continue
            }
            while (i < line.length && line[i].isWhitespace()) i++
            if (i >= line.length) return null
            return i to line.substring(i)
        }
        return null
    }

    /** Chinese and Japanese keywords run straight into the step text; most languages need a space. */
    private fun isSpaceRequired(language: String, keyword: String): Boolean = try {
        keywordProvider.isSpaceRequiredAfterKeyword(language, keyword)
    } catch (_: RuntimeException) {
        true
    }
}
