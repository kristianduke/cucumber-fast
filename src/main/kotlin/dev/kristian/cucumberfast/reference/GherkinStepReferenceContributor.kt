package dev.kristian.cucumberfast.reference

import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.TokenType
import com.intellij.psi.tree.TokenSet
import com.intellij.util.ProcessingContext
import org.jetbrains.plugins.cucumber.psi.GherkinElementTypes
import org.jetbrains.plugins.cucumber.psi.GherkinStep
import org.jetbrains.plugins.cucumber.psi.GherkinTokenTypes

/**
 * Puts a [FastCucumberStepReference] on the text of every Gherkin step.
 *
 * The Gherkin plugin contributes its own reference to the same range. That one now resolves to
 * nothing immediately — see `JavaCucumberExtension` — so the platform picks this one, which is the
 * only reference actually doing the lookup.
 */
class GherkinStepReferenceContributor : PsiReferenceContributor() {

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(GherkinStep::class.java), Provider)
    }

    private object Provider : PsiReferenceProvider() {

        /** The tokens that make up a step's text, mirroring how the Gherkin plugin ranges its own. */
        private val TEXT_TOKENS = TokenSet.create(
            GherkinTokenTypes.TEXT,
            GherkinTokenTypes.STEP_PARAMETER_TEXT,
            GherkinTokenTypes.STEP_PARAMETER_BRACE,
            GherkinElementTypes.STEP_PARAMETER,
        )

        private val TEXT_AND_SPACE = TokenSet.orSet(TEXT_TOKENS, TokenSet.create(TokenType.WHITE_SPACE))

        override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
            val step = element as? GherkinStep ?: return PsiReference.EMPTY_ARRAY
            val first = step.node.findChildByType(TEXT_TOKENS) ?: return PsiReference.EMPTY_ARRAY

            // Take the run of text tokens, stopping before any trailing whitespace so the reference
            // does not underline the gap before a table or doc string.
            var last = first
            var node = first.treeNext
            while (node != null && TEXT_AND_SPACE.contains(node.elementType)) {
                if (node.elementType != TokenType.WHITE_SPACE) last = node
                node = node.treeNext
            }

            val range = TextRange(first.textRange.startOffset, last.textRange.endOffset)
                .shiftLeft(step.textRange.startOffset)
            return arrayOf(FastCucumberStepReference(step, range))
        }
    }
}
