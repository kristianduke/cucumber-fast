package dev.kristian.cucumberfast.expression

import org.jetbrains.plugins.cucumber.CucumberUtil
import org.jetbrains.plugins.cucumber.ParameterTypeManager
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * A step definition pattern — either a Cucumber expression (`I have {int} cukes`) or a regular
 * expression (`^I have (\d+) cukes$`) — compiled once and shared application-wide.
 *
 * Two derived properties are what make matching cheap:
 *
 *  - [literalPrefix] is the leading run of characters the step text has to start with. When it
 *    does not, the step is rejected with a `String.startsWith` instead of a regex run.
 *  - [indexKey] buckets the pattern by the first one or two words of that prefix, so a step only
 *    ever gets compared against the handful of definitions that could plausibly match it.
 *
 * Both are only sound for *anchored* patterns. IntelliJ matches step definitions with
 * [java.util.regex.Matcher.find] rather than `matches`, so an unanchored regex such as
 * `cukes in my belly` legitimately matches somewhere in the middle of a step. Those patterns keep
 * the slow path: [anchored] is false, [indexKey] is [ANY_KEY], and every step is regex-tested.
 *
 * Both properties are derived from the pattern text alone, never from resolved parameter types.
 * That matters because the index computes them without project context: a project-defined
 * `{colour}` changes what the pattern *matches*, but not which bucket it belongs in. Resolving
 * those types is [withParameterTypes], applied later, on the query side.
 */
class StepPattern private constructor(
    /** The pattern exactly as written in the step definition annotation. */
    val source: String,
    val isRegex: Boolean,
    val anchored: Boolean,
    /** Lowercased; empty when the pattern opens with a placeholder or a regex construct. */
    val literalPrefix: String,
    /** Names inside `{…}`, empty for a regular expression. */
    val parameterTypeNames: List<String>,
    private val regex: Pattern?,
) {

    /** Bucket this pattern is indexed under. See [lookupKeysForStepText] for the reverse direction. */
    val indexKey: String =
        if (anchored) keyForPrefix(literalPrefix, isComplete = literalPrefix.length == source.length) else ANY_KEY

    /** True when this pattern names a parameter type that is not one of Cucumber's built-ins. */
    val usesCustomParameterTypes: Boolean
        get() = parameterTypeNames.any { StandardParameterTypes.getParameterTypeValue(it) == null }

    /** True when [stepText] is handled by this step definition. */
    fun matches(stepText: String): Boolean {
        val regex = regex ?: return false
        if (anchored && literalPrefix.isNotEmpty() && !stepText.startsWith(literalPrefix, ignoreCase = true)) {
            return false
        }
        return try {
            regex.matcher(stepText).find()
        } catch (_: StackOverflowError) {
            // Pathological backtracking on a hand-written regex must not take the IDE down.
            false
        }
    }

    /**
     * The same pattern with project-defined `@ParameterType` declarations resolved. Classification,
     * prefix and bucket are unchanged by construction — only the regex differs.
     */
    fun withParameterTypes(parameterTypes: ParameterTypeManager): StepPattern =
        if (isRegex) this else StepPattern(source, isRegex, anchored, literalPrefix, parameterTypeNames, buildRegex(source, parameterTypes))

    override fun toString(): String = source

    companion object {
        /** Bucket for patterns whose text cannot be predicted from the step text. Always searched. */
        const val ANY_KEY: String = ""

        private const val CACHE_LIMIT = 20_000

        private val cache = ConcurrentHashMap<String, StepPattern>()

        fun compile(source: String): StepPattern {
            cache[source]?.let { return it }
            val compiled = doCompile(source)
            // Unbounded growth would pin memory in a project with generated step definitions.
            if (cache.size < CACHE_LIMIT) cache[source] = compiled
            return compiled
        }

        /**
         * Keys under which a step definition matching [stepText] may be indexed: one word, two
         * words, and the catch-all bucket.
         */
        fun lookupKeysForStepText(stepText: String): List<String> {
            val words = words(stepText, limit = 2)
            return when (words.size) {
                0 -> listOf(ANY_KEY)
                1 -> listOf(words[0], ANY_KEY)
                else -> listOf(words[0], words[0] + ' ' + words[1], ANY_KEY)
            }
        }

        /** Keys a Gherkin step with this text is indexed under (see `GherkinStepIndex`). */
        fun indexKeysForStepText(stepText: String): List<String> {
            val words = words(stepText, limit = 2)
            return when (words.size) {
                0 -> listOf(ANY_KEY)
                1 -> listOf(words[0])
                else -> listOf(words[0], words[0] + ' ' + words[1])
            }
        }

        private fun doCompile(source: String): StepPattern {
            val parameterTypeNames = parameterTypeNames(source)
            val isRegex = looksLikeRegex(source, parameterTypeNames)
            // A Cucumber expression always matches the whole step; a regex only anchors if it says so.
            val anchored = if (isRegex) source.startsWith("^") || source.startsWith("\\A") else true
            val prefix = if (isRegex) regexLiteralPrefix(source) else expressionLiteralPrefix(source)
            val regex = if (isRegex) compileRegex(source) else buildRegex(source, StandardParameterTypes)
            return StepPattern(
                source = source,
                isRegex = isRegex,
                anchored = anchored,
                literalPrefix = prefix.lowercase(),
                parameterTypeNames = if (isRegex) emptyList() else parameterTypeNames,
                regex = regex,
            )
        }

        /**
         * Cucumber JVM treats an annotation value as a regular expression when it is anchored, and
         * as a Cucumber expression otherwise. IntelliJ instead requires a `{placeholder}` before it
         * will call something an expression, which leaves a plain-text pattern
         * (`I am a cat/dog`, `there are no cukes`) classified as an unanchored regex — matching
         * more loosely than the runtime does, and forfeiting the bucket that makes lookup cheap.
         *
         * The runtime's rule is used here, widened for patterns carrying regex syntax that a
         * Cucumber expression could never contain, so a hand-written unanchored regex keeps working.
         */
        private fun looksLikeRegex(source: String, parameterTypeNames: List<String>): Boolean =
            source.startsWith("^") || source.endsWith("$") ||
                source.contains('\\') || source.contains('[') || source.contains(".*") || source.contains(".+") ||
                // `a{2}` is a quantifier, not a parameter type.
                parameterTypeNames.any { name -> name.isNotEmpty() && name.all { it.isDigit() || it == ',' } }

        private fun parameterTypeNames(source: String): List<String> {
            val names = ArrayList<String>(2)
            CucumberUtil.processParameterTypesInCucumberExpression(source) { range ->
                if (range.length >= 2) {
                    names.add(source.substring(range.startOffset + 1, range.endOffset - 1))
                }
                true
            }
            return names
        }

        private fun buildRegex(source: String, parameterTypes: ParameterTypeManager): Pattern? =
            compileRegex(CucumberUtil.buildRegexpFromCucumberExpression(source, parameterTypes))

        private fun compileRegex(regexText: String): Pattern? = try {
            Pattern.compile(normalizeAnchors(regexText))
        } catch (_: PatternSyntaxException) {
            null
        }

        /** `\A`/`\z` are what Cucumber emits; `Pattern` understands them, but `^`/`$` read better in tests. */
        private fun normalizeAnchors(regexText: String): String {
            var result = regexText
            if (result.startsWith("\\A")) result = "^" + result.substring(2)
            if (result.endsWith("\\z")) result = result.dropLast(2) + "$"
            return result
        }

        /** Characters that end the literal run of a Cucumber expression. */
        private const val EXPRESSION_STOP_CHARS = "{}()/\\"

        private fun expressionLiteralPrefix(expression: String): String {
            val end = expression.indexOfFirst { it in EXPRESSION_STOP_CHARS }
            if (end < 0) return expression
            val prefix = expression.substring(0, end)
            if (expression[end] != '/') return prefix
            // Alternation replaces the word in front of it — `cat/dog` matches "dog" too — so the
            // literal run ends before that word, not at the slash.
            val lastSpace = prefix.indexOfLast { it.isWhitespace() }
            return if (lastSpace < 0) "" else prefix.substring(0, lastSpace + 1)
        }

        /** Characters that end the literal run of a regular expression. */
        private const val REGEX_STOP_CHARS = "\\[](){}.*+?|$"

        private fun regexLiteralPrefix(regex: String): String {
            val body = regex.removePrefix("^").let { if (it.startsWith("\\A")) it.substring(2) else it }
            val end = body.indexOfFirst { it in REGEX_STOP_CHARS }
            return if (end < 0) body else body.substring(0, end)
        }

        /**
         * The bucket for a literal prefix: its first one or two *whole* words. A trailing partial
         * word ("cuke" in `cuke{int}s`) is dropped — the step text would not reproduce it.
         */
        private fun keyForPrefix(prefix: String, isComplete: Boolean): String {
            val words = words(prefix, limit = 3).toMutableList()
            val endsMidWord = !isComplete && prefix.isNotEmpty() && !prefix.last().isWhitespace()
            if (endsMidWord && words.isNotEmpty()) words.removeAt(words.size - 1)
            return when {
                words.isEmpty() -> ANY_KEY
                words.size == 1 -> words[0]
                else -> words[0] + ' ' + words[1]
            }
        }

        private fun words(text: String, limit: Int): List<String> {
            val result = ArrayList<String>(limit)
            var i = 0
            while (i < text.length && result.size < limit) {
                while (i < text.length && text[i].isWhitespace()) i++
                val start = i
                while (i < text.length && !text[i].isWhitespace()) i++
                if (i > start) result.add(text.substring(start, i).lowercase())
            }
            return result
        }
    }
}
