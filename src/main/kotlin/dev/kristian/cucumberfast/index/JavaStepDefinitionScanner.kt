package dev.kristian.cucumberfast.index

import com.intellij.lang.java.lexer.JavaLexer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.pom.java.LanguageLevel
import com.intellij.psi.JavaTokenType
import com.intellij.psi.TokenType

/**
 * Finds step definition annotations in Java source text with the Java lexer alone — no parsing, no
 * PSI, no resolve. This runs inside the file index, so it has to stay proportional to file size.
 *
 * Anything the lexer can get wrong on its own (an `@Given` from an unrelated library, an annotation
 * on something that is not a method) is filtered later, when a step actually matches and the owning
 * method gets resolved. Getting it wrong here only costs a wasted candidate, never a wrong result.
 */
object JavaStepDefinitionScanner {

    /**
     * Simple names of the Cucumber JVM step annotations. Localized annotation packages
     * (`io.cucumber.java.de` and friends) are not covered yet.
     */
    val STEP_ANNOTATIONS: Set<String> = setOf("Given", "When", "Then", "And", "But")

    fun scan(text: CharSequence): List<StepDefinitionEntry> {
        if (!mayContainStepDefinition(text)) return emptyList()

        val result = ArrayList<StepDefinitionEntry>()
        val lexer = JavaLexer(LanguageLevel.HIGHEST)
        lexer.start(text)

        var atOffset = -1
        var lastIdentifier: String? = null

        while (lexer.tokenType != null) {
            val type = lexer.tokenType
            when {
                type == TokenType.WHITE_SPACE || type == JavaTokenType.C_STYLE_COMMENT ||
                    type == JavaTokenType.END_OF_LINE_COMMENT -> {
                    lexer.advance()
                    continue
                }

                type == JavaTokenType.AT -> {
                    atOffset = lexer.tokenStart
                    lastIdentifier = null
                }

                atOffset < 0 -> Unit // not inside an annotation; nothing to track

                type == JavaTokenType.IDENTIFIER ->
                    lastIdentifier = text.substring(lexer.tokenStart, lexer.tokenEnd)

                type == JavaTokenType.DOT -> Unit // qualified annotation name: keep reading identifiers

                type == JavaTokenType.LPARENTH -> {
                    if (lastIdentifier in STEP_ANNOTATIONS) {
                        val expression = readFirstStringArgument(lexer, text)
                        if (expression != null) {
                            result.add(StepDefinitionEntry(lastIdentifier!!, expression, atOffset))
                        }
                    }
                    atOffset = -1
                    lastIdentifier = null
                    continue // readFirstStringArgument already advanced past the argument list
                }

                else -> {
                    // A marker annotation such as `@Override`, or anything else that ends the name.
                    atOffset = -1
                    lastIdentifier = null
                }
            }
            lexer.advance()
        }
        return result
    }

    /**
     * Consumes the annotation's argument list, starting at its `(`, and returns the first string
     * literal in it — which is the pattern for both `@Given("...")` and `@Given(value = "...")`.
     */
    private fun readFirstStringArgument(lexer: JavaLexer, text: CharSequence): String? {
        var depth = 0
        var expression: String? = null
        while (lexer.tokenType != null) {
            when (lexer.tokenType) {
                JavaTokenType.LPARENTH -> depth++
                JavaTokenType.RPARENTH -> {
                    depth--
                    if (depth == 0) {
                        lexer.advance()
                        return expression
                    }
                }

                JavaTokenType.STRING_LITERAL -> if (expression == null) {
                    expression = decodeStringLiteral(text.substring(lexer.tokenStart, lexer.tokenEnd))
                }
            }
            lexer.advance()
        }
        return expression
    }

    /** `"I have \\d+ cukes"` as written in source becomes the pattern the runtime sees. */
    private fun decodeStringLiteral(raw: String): String? {
        if (raw.length < 2 || !raw.startsWith('"') || !raw.endsWith('"')) return null
        return StringUtil.unescapeStringCharacters(raw.substring(1, raw.length - 1)).takeIf { it.isNotEmpty() }
    }

    /** Cheap reject so files with no step definitions are never lexed. */
    private fun mayContainStepDefinition(text: CharSequence): Boolean =
        STEP_ANNOTATIONS.any { StringUtil.contains(text, it) }
}
