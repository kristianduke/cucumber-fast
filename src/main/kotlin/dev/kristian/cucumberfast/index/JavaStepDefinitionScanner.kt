package dev.kristian.cucumberfast.index

import com.intellij.lang.java.lexer.JavaLexer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.pom.java.LanguageLevel
import com.intellij.psi.JavaTokenType
import com.intellij.psi.TokenType

/**
 * Finds step definitions and `@ParameterType` declarations in Java source text with the Java lexer
 * alone — no parsing, no PSI, no resolve. This runs inside the file index, so it has to stay
 * proportional to file size.
 *
 * Anything the lexer can get wrong on its own (a `@Given` from an unrelated library, an annotation
 * on something that is not a method) is filtered later, when a step actually matches and the owning
 * method gets resolved. Getting it wrong here only costs a wasted candidate, never a wrong result.
 */
object JavaStepDefinitionScanner {

    /** The English step annotations, always recognised even in a project without the dependency. */
    val ENGLISH_STEP_KEYWORDS: Set<String> = setOf("Given", "When", "Then", "And", "But")

    private const val PARAMETER_TYPE_ANNOTATION = "ParameterType"

    /** Packages whose members are Cucumber step annotations, one sub-package per language. */
    private val STEP_ANNOTATION_PACKAGES = listOf("io.cucumber.java.", "cucumber.api.java.")

    /** Lambda-style step definitions live in classes implementing one of these interfaces. */
    private const val JAVA8_PACKAGE = "io.cucumber.java8"

    data class Result(
        val stepDefinitions: List<StepDefinitionEntry>,
        val parameterTypes: List<ParameterTypeEntry>,
    ) {
        val isEmpty: Boolean get() = stepDefinitions.isEmpty() && parameterTypes.isEmpty()

        companion object {
            val EMPTY = Result(emptyList(), emptyList())
        }
    }

    fun scan(text: CharSequence): Result {
        if (!mayContainStepDefinition(text)) return Result.EMPTY

        val steps = ArrayList<StepDefinitionEntry>()
        val parameterTypes = ArrayList<ParameterTypeEntry>()
        // Localized step annotations are named by their import: `io.cucumber.java.de.Angenommen`.
        val importedStepAnnotations = HashSet(ENGLISH_STEP_KEYWORDS)
        var lambdaStepsPossible = false
        var localeWildcardImported = false

        val lexer = JavaLexer(LanguageLevel.HIGHEST)
        lexer.start(text)

        var pendingParameterType: Map<String, String>? = null
        var pendingParameterTypeOffset = -1
        var previousType = TokenType.WHITE_SPACE

        while (lexer.tokenType != null) {
            val type = lexer.tokenType
            if (type == TokenType.WHITE_SPACE || type == JavaTokenType.C_STYLE_COMMENT ||
                type == JavaTokenType.END_OF_LINE_COMMENT
            ) {
                lexer.advance()
                continue
            }

            when {
                type == JavaTokenType.IMPORT_KEYWORD -> {
                    val imported = readImport(lexer, text)
                    if (imported != null) {
                        if (imported.startsWith(JAVA8_PACKAGE)) lambdaStepsPossible = true
                        if (isStepAnnotationName(imported)) {
                            val simpleName = imported.substringAfterLast('.')
                            // `import io.cucumber.java.de.*` names no annotation, so fall back to
                            // accepting any name and letting resolution check the package.
                            if (simpleName == "*") localeWildcardImported = true else importedStepAnnotations.add(simpleName)
                        }
                    }
                    previousType = JavaTokenType.SEMICOLON
                    continue
                }

                type == JavaTokenType.AT -> {
                    val offset = lexer.tokenStart
                    val annotation = readAnnotation(lexer, text)
                    if (annotation != null) {
                        val simpleName = annotation.name.substringAfterLast('.')
                        val qualifier = annotation.name.substringBeforeLast('.', "")
                        val isStep = when {
                            annotation.name.contains('.') -> isStepAnnotationName(annotation.name)
                            simpleName == PARAMETER_TYPE_ANNOTATION -> false
                            else -> (simpleName in importedStepAnnotations || localeWildcardImported) &&
                                qualifierIsNotForeign(qualifier)
                        }
                        when {
                            isStep -> annotation.arguments["value"]?.let {
                                steps.add(StepDefinitionEntry(simpleName, it, offset, StepDefinitionKind.ANNOTATION))
                            }

                            simpleName == PARAMETER_TYPE_ANNOTATION -> {
                                pendingParameterType = annotation.arguments
                                pendingParameterTypeOffset = offset
                            }
                        }
                    }
                    previousType = JavaTokenType.RPARENTH
                    continue
                }

                type == JavaTokenType.IDENTIFIER -> {
                    val name = text.substring(lexer.tokenStart, lexer.tokenEnd)
                    val offset = lexer.tokenStart
                    lexer.advance()
                    skipTrivia(lexer)
                    if (lexer.tokenType == JavaTokenType.LPARENTH) {
                        // `@ParameterType` names itself after the method it annotates, unless the
                        // annotation said otherwise; this identifier is that method's name.
                        val declared = pendingParameterType
                        if (declared != null) {
                            val regex = declared["value"]
                            if (regex != null) {
                                parameterTypes.add(
                                    ParameterTypeEntry(declared["name"] ?: name, regex, pendingParameterTypeOffset),
                                )
                            }
                            pendingParameterType = null
                        } else if (lambdaStepsPossible &&
                            name in ENGLISH_STEP_KEYWORDS &&
                            previousType != JavaTokenType.DOT
                        ) {
                            readArguments(lexer, text)["value"]?.let {
                                steps.add(StepDefinitionEntry(name, it, offset, StepDefinitionKind.LAMBDA))
                            }
                            previousType = JavaTokenType.RPARENTH
                            continue
                        }
                    }
                    previousType = JavaTokenType.IDENTIFIER
                    continue
                }

                // A declaration body ends any pending @ParameterType that never reached a method.
                type == JavaTokenType.SEMICOLON || type == JavaTokenType.RBRACE -> pendingParameterType = null
            }

            previousType = type
            lexer.advance()
        }
        return if (steps.isEmpty() && parameterTypes.isEmpty()) Result.EMPTY else Result(steps, parameterTypes)
    }

    /**
     * Step annotations live one package below the locale: `io.cucumber.java.en.Given`. That extra
     * segment is what separates them from Cucumber's other annotations — `io.cucumber.java.Before`,
     * `io.cucumber.java.ParameterType` — which sit directly in the base package and are not steps.
     */
    private fun isStepAnnotationName(qualifiedName: String): Boolean =
        STEP_ANNOTATION_PACKAGES.any { prefix ->
            qualifiedName.startsWith(prefix) && qualifiedName.substring(prefix.length).contains('.')
        }

    /** `@org.junit.Given` is not Cucumber's, whatever it is called. */
    private fun qualifierIsNotForeign(qualifier: String): Boolean =
        qualifier.isEmpty() || STEP_ANNOTATION_PACKAGES.any { "$qualifier.".startsWith(it) || it.startsWith("$qualifier.") }

    private class Annotation(val name: String, val arguments: Map<String, String>)

    /** Reads the annotation name and, if present, its argument list. Positioned just after the `@`. */
    private fun readAnnotation(lexer: JavaLexer, text: CharSequence): Annotation? {
        lexer.advance()
        skipTrivia(lexer)
        val name = StringBuilder()
        while (lexer.tokenType == JavaTokenType.IDENTIFIER) {
            name.append(text.substring(lexer.tokenStart, lexer.tokenEnd))
            lexer.advance()
            skipTrivia(lexer)
            if (lexer.tokenType != JavaTokenType.DOT) break
            name.append('.')
            lexer.advance()
            skipTrivia(lexer)
        }
        if (name.isEmpty()) return null
        val arguments = if (lexer.tokenType == JavaTokenType.LPARENTH) readArguments(lexer, text) else emptyMap()
        return Annotation(name.toString(), arguments)
    }

    /**
     * Consumes an argument list starting at its `(` and returns the string-valued arguments by
     * name. A bare string is `value`, so `@Given("x")` and `@Given(value = "x", timeout = 1)` and
     * `@ParameterType(name = "colour", value = "red|blue")` all read correctly.
     */
    private fun readArguments(lexer: JavaLexer, text: CharSequence): Map<String, String> {
        val arguments = HashMap<String, String>(2)
        var depth = 0
        var pendingName: String? = null
        var sawAssignment = false
        while (lexer.tokenType != null) {
            when (lexer.tokenType) {
                JavaTokenType.LPARENTH -> depth++
                JavaTokenType.RPARENTH -> {
                    depth--
                    if (depth == 0) {
                        lexer.advance()
                        return arguments
                    }
                }

                JavaTokenType.IDENTIFIER -> if (depth == 1) {
                    pendingName = text.substring(lexer.tokenStart, lexer.tokenEnd)
                    sawAssignment = false
                }

                JavaTokenType.EQ -> if (depth == 1) sawAssignment = true

                JavaTokenType.COMMA -> if (depth == 1) {
                    pendingName = null
                    sawAssignment = false
                }

                JavaTokenType.STRING_LITERAL -> if (depth == 1) {
                    val value = decodeStringLiteral(text.substring(lexer.tokenStart, lexer.tokenEnd))
                    if (value != null) {
                        val key = if (sawAssignment && pendingName != null) pendingName else "value"
                        arguments.putIfAbsent(key!!, value)
                    }
                    pendingName = null
                    sawAssignment = false
                }
            }
            lexer.advance()
        }
        return arguments
    }

    /** Reads a qualified import name; returns null for anything unexpected. */
    private fun readImport(lexer: JavaLexer, text: CharSequence): String? {
        lexer.advance()
        skipTrivia(lexer)
        val name = StringBuilder()
        while (lexer.tokenType != null && lexer.tokenType != JavaTokenType.SEMICOLON) {
            when (lexer.tokenType) {
                JavaTokenType.IDENTIFIER, JavaTokenType.DOT, JavaTokenType.ASTERISK ->
                    name.append(text.substring(lexer.tokenStart, lexer.tokenEnd))

                JavaTokenType.STATIC_KEYWORD -> Unit
                else -> return null
            }
            lexer.advance()
            skipTrivia(lexer)
        }
        return name.toString().takeIf { it.isNotEmpty() }
    }

    private fun skipTrivia(lexer: JavaLexer) {
        while (lexer.tokenType == TokenType.WHITE_SPACE ||
            lexer.tokenType == JavaTokenType.C_STYLE_COMMENT ||
            lexer.tokenType == JavaTokenType.END_OF_LINE_COMMENT
        ) {
            lexer.advance()
        }
    }

    /** `"I have \\d+ cukes"` as written in source becomes the pattern the runtime sees. */
    private fun decodeStringLiteral(raw: String): String? {
        if (raw.length < 2 || !raw.startsWith('"') || !raw.endsWith('"')) return null
        return StringUtil.unescapeStringCharacters(raw.substring(1, raw.length - 1)).takeIf { it.isNotEmpty() }
    }

    /** Cheap reject so files with nothing of interest are never lexed. */
    private fun mayContainStepDefinition(text: CharSequence): Boolean =
        StringUtil.contains(text, "cucumber") ||
            ENGLISH_STEP_KEYWORDS.any { StringUtil.contains(text, it) }
}
