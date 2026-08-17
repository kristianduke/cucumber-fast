package dev.kristian.cucumberfast.expression

import com.intellij.psi.PsiElement
import org.jetbrains.plugins.cucumber.CucumberUtil
import org.jetbrains.plugins.cucumber.ParameterTypeManager

/**
 * The parameter types every Cucumber runtime defines out of the box.
 *
 * Project-defined types (`@ParameterType`) are not resolved yet; an expression using one keeps its
 * placeholder, which [CucumberUtil.buildRegexpFromCucumberExpression] leaves unanchored, so those
 * step definitions fall back to plain regex matching rather than matching incorrectly.
 */
object StandardParameterTypes : ParameterTypeManager {

    private val types: Map<String, String> = buildMap {
        putAll(CucumberUtil.STANDARD_PARAMETER_TYPES)
        put("bigdecimal", "-?\\d*[.,]?\\d+")
        put("biginteger", "-?\\d+")
        put("byte", "-?\\d+")
        put("short", "-?\\d+")
        put("long", "-?\\d+")
        put("double", "-?\\d*[.,]?\\d+")
    }

    override fun getParameterTypeValue(name: String): String? = types[name]

    override fun getParameterTypeDeclaration(name: String): PsiElement? = null
}
