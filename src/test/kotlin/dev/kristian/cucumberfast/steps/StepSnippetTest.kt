package dev.kristian.cucumberfast.steps

import org.junit.Assert.assertEquals
import org.junit.Test

class StepSnippetTest {

    @Test
    fun `numbers become int parameters`() {
        val snippet = StepSnippet.forStepText("I have 42 cukes")
        assertEquals("I have {int} cukes", snippet.expression)
        assertEquals("i_have_cukes", snippet.methodName)
        assertEquals(listOf(StepSnippet.Parameter("Integer", "int1")), snippet.parameters)
    }

    @Test
    fun `quoted text becomes a string parameter`() {
        val snippet = StepSnippet.forStepText("""the user "bob" logs in""")
        assertEquals("the user {string} logs in", snippet.expression)
        assertEquals("the_user_logs_in", snippet.methodName)
        assertEquals(listOf(StepSnippet.Parameter("String", "string1")), snippet.parameters)
    }

    @Test
    fun `decimals become float parameters`() {
        val snippet = StepSnippet.forStepText("I wait 1.5 seconds")
        assertEquals("I wait {float} seconds", snippet.expression)
        assertEquals(listOf(StepSnippet.Parameter("Double", "float1")), snippet.parameters)
    }

    @Test
    fun `parameters of the same type are numbered`() {
        val snippet = StepSnippet.forStepText("""I move 3 cukes from "a" to "b" in 2 steps""")
        assertEquals("I move {int} cukes from {string} to {string} in {int} steps", snippet.expression)
        assertEquals(
            listOf(
                StepSnippet.Parameter("Integer", "int1"),
                StepSnippet.Parameter("String", "string1"),
                StepSnippet.Parameter("String", "string2"),
                StepSnippet.Parameter("Integer", "int2"),
            ),
            snippet.parameters,
        )
    }

    @Test
    fun `digits inside a word are left alone`() {
        val snippet = StepSnippet.forStepText("the file is utf8 encoded")
        assertEquals("the file is utf8 encoded", snippet.expression)
        assertEquals(emptyList<StepSnippet.Parameter>(), snippet.parameters)
    }

    @Test
    fun `expression syntax in the step text is escaped`() {
        val snippet = StepSnippet.forStepText("I press (the) button")
        assertEquals("""I press \(the\) button""", snippet.expression)
        assertEquals("i_press_the_button", snippet.methodName)
    }

    @Test
    fun `a step of only parameters still gets a method name`() {
        assertEquals("step", StepSnippet.forStepText("42").methodName)
    }
}
