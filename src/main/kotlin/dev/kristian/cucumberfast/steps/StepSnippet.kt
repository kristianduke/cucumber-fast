package dev.kristian.cucumberfast.steps

/**
 * Turns the text of an undefined step into the step definition Cucumber would suggest for it:
 * literal values become parameter types, and the rest becomes the method name.
 *
 *     I have 42 cukes in my "big" belly
 *     -> @Given("I have {int} cukes in my {string} belly")
 *        public void i_have_cukes_in_my_belly(Integer int1, String string1)
 */
object StepSnippet {

    data class Parameter(val type: String, val name: String)

    data class Snippet(val expression: String, val methodName: String, val parameters: List<Parameter>)

    /** Characters a Cucumber expression treats as syntax, and so must be escaped in literal text. */
    private const val ESCAPED_CHARS = "{}()/\\"

    fun forStepText(stepText: String): Snippet {
        val expression = StringBuilder()
        val parameters = ArrayList<Parameter>()
        val counts = HashMap<String, Int>()

        fun addParameter(cucumberType: String, javaType: String) {
            val ordinal = counts.merge(cucumberType, 1, Int::plus)!!
            expression.append('{').append(cucumberType).append('}')
            parameters.add(Parameter(javaType, cucumberType + ordinal))
        }

        var i = 0
        while (i < stepText.length) {
            val c = stepText[i]
            when {
                c == '"' || c == '\'' -> {
                    val close = stepText.indexOf(c, i + 1)
                    if (close < 0) {
                        expression.append(escape(c))
                        i++
                    } else {
                        addParameter("string", "String")
                        i = close + 1
                    }
                }

                c.isDigit() && startsNumber(stepText, i) -> {
                    var end = i
                    while (end < stepText.length && stepText[end].isDigit()) end++
                    val isFloat = end + 1 < stepText.length && stepText[end] == '.' && stepText[end + 1].isDigit()
                    if (isFloat) {
                        end++
                        while (end < stepText.length && stepText[end].isDigit()) end++
                        addParameter("float", "Double")
                    } else {
                        addParameter("int", "Integer")
                    }
                    i = end
                }

                else -> {
                    expression.append(escape(c))
                    i++
                }
            }
        }

        return Snippet(expression.toString(), methodName(expression.toString()), parameters)
    }

    /** A digit only starts a value when it is not in the middle of a word, as in `utf8`. */
    private fun startsNumber(text: String, index: Int): Boolean =
        index == 0 || !text[index - 1].isLetterOrDigit()

    private fun escape(c: Char): String = if (c in ESCAPED_CHARS) "\\$c" else c.toString()

    /** `I have {int} cukes` becomes `i_have_cukes`; placeholders contribute nothing. */
    private fun methodName(expression: String): String {
        val withoutPlaceholders = PLACEHOLDER.replace(expression, " ")
        val words = withoutPlaceholders
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotEmpty() }
            .map { it.lowercase() }
        return if (words.isEmpty()) "step" else words.joinToString("_")
    }

    private val PLACEHOLDER = Regex("\\{[^}]*}")
}
