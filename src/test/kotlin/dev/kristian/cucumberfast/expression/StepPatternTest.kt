package dev.kristian.cucumberfast.expression

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StepPatternTest {

    @Test
    fun `cucumber expression matches a step`() {
        val pattern = StepPattern.compile("I have {int} cukes in my belly")
        assertTrue(pattern.matches("I have 42 cukes in my belly"))
        assertFalse(pattern.matches("I have some cukes in my belly"))
    }

    @Test
    fun `cucumber expression is anchored and keyed on its first two words`() {
        val pattern = StepPattern.compile("I have {int} cukes")
        assertTrue(pattern.anchored)
        assertEquals("i have ", pattern.literalPrefix)
        assertEquals("i have", pattern.indexKey)
    }

    @Test
    fun `a pattern opening with a placeholder falls into the catch-all bucket`() {
        val pattern = StepPattern.compile("{int} cukes are left")
        assertEquals(StepPattern.ANY_KEY, pattern.indexKey)
        assertTrue(pattern.matches("42 cukes are left"))
    }

    @Test
    fun `a partial leading word is not used as a key`() {
        val pattern = StepPattern.compile("cuke{int} is eaten")
        assertEquals(StepPattern.ANY_KEY, pattern.indexKey)
    }

    @Test
    fun `anchored regex uses its literal prefix`() {
        val pattern = StepPattern.compile("^the user (.*) logs in$")
        assertTrue(pattern.anchored)
        assertEquals("the user ", pattern.literalPrefix)
        assertEquals("the user", pattern.indexKey)
        assertTrue(pattern.matches("the user bob logs in"))
        assertFalse(pattern.matches("the admin bob logs in"))
    }

    @Test
    fun `unanchored regex keeps the slow path`() {
        val pattern = StepPattern.compile("""cukes \d+ in my belly""")
        assertFalse(pattern.anchored)
        assertEquals(StepPattern.ANY_KEY, pattern.indexKey)
        // IntelliJ matches step definitions with find(), so this legitimately matches mid-step.
        assertTrue(pattern.matches("I have cukes 42 in my belly"))
    }

    @Test
    fun `plain text is a cucumber expression, as the runtime treats it`() {
        val pattern = StepPattern.compile("there are no cukes left")
        assertTrue(pattern.anchored)
        assertEquals("there are", pattern.indexKey)
        assertTrue(pattern.matches("there are no cukes left"))
        assertFalse(pattern.matches("I check that there are no cukes left"))
    }

    @Test
    fun `step text looks up the buckets its definitions can be in`() {
        assertEquals(listOf("i", "i have", StepPattern.ANY_KEY), StepPattern.lookupKeysForStepText("I have 42 cukes"))
        assertEquals(listOf("go", StepPattern.ANY_KEY), StepPattern.lookupKeysForStepText("go"))
        assertEquals(listOf(StepPattern.ANY_KEY), StepPattern.lookupKeysForStepText("   "))
    }

    @Test
    fun `a definition key is always one of the step text keys`() {
        val steps = listOf(
            "I have 42 cukes" to "I have {int} cukes",
            "the user bob logs in" to "^the user (.*) logs in$",
            "go" to "go",
        )
        for ((stepText, expression) in steps) {
            val pattern = StepPattern.compile(expression)
            assertTrue(
                "$expression is indexed under '${pattern.indexKey}', which '$stepText' never queries",
                pattern.indexKey in StepPattern.lookupKeysForStepText(stepText),
            )
            assertTrue(pattern.matches(stepText))
        }
    }

    @Test
    fun `optional text and alternation still match`() {
        assertTrue(StepPattern.compile("I have {int} cuke(s)").matches("I have 1 cuke"))
        assertTrue(StepPattern.compile("I have {int} cuke(s)").matches("I have 2 cukes"))
        assertTrue(StepPattern.compile("I am a cat/dog").matches("I am a dog"))
    }

    @Test
    fun `an unknown parameter type does not match everything`() {
        val pattern = StepPattern.compile("I have {colour} cukes")
        assertFalse(pattern.anchored)
        assertFalse(pattern.matches("something entirely different"))
    }

    @Test
    fun `a broken regex never matches`() {
        assertFalse(StepPattern.compile("^I have (unclosed$").matches("I have unclosed"))
    }

    @Test
    fun `matching is case sensitive on the prefix as the regex is`() {
        val pattern = StepPattern.compile("I have {int} cukes")
        assertFalse(pattern.matches("i have 42 cukes"))
    }
}
