package dev.kristian.cucumberfast.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JavaStepDefinitionScannerTest {

    @Test
    fun `finds step definitions and their patterns`() {
        val source = """
            package steps;
            import io.cucumber.java.en.Given;
            import io.cucumber.java.en.Then;

            public class CukeSteps {
                @Given("I have {int} cukes")
                public void iHaveCukes(int count) {}

                @Then("^I am (.*)$")
                public void iAm(String mood) {}
            }
        """.trimIndent()

        assertEquals(
            listOf("Given" to "I have {int} cukes", "Then" to "^I am (.*)$"),
            JavaStepDefinitionScanner.scan(source).map { it.annotationName to it.expression },
        )
    }

    @Test
    fun `offsets point at the annotation`() {
        val source = "class S {\n    @Given(\"a step\")\n    void s() {}\n}"
        val entry = JavaStepDefinitionScanner.scan(source).single()
        assertTrue(source.substring(entry.annotationOffset).startsWith("@Given"))
    }

    @Test
    fun `reads the named value attribute`() {
        val source = """class S { @Given(value = "a step", timeout = 1000) void s() {} }"""
        assertEquals(listOf("a step"), JavaStepDefinitionScanner.scan(source).map { it.expression })
    }

    @Test
    fun `reads fully qualified annotations`() {
        val source = """class S { @io.cucumber.java.en.When("a step") void s() {} }"""
        assertEquals(listOf("a step"), JavaStepDefinitionScanner.scan(source).map { it.expression })
    }

    @Test
    fun `unescapes the pattern as the compiler would`() {
        val source = """class S { @Given("^I have (\\d+) cukes$") void s() {} }"""
        assertEquals("""^I have (\d+) cukes$""", JavaStepDefinitionScanner.scan(source).single().expression)
    }

    @Test
    fun `ignores annotations in comments and strings`() {
        val source = """
            class S {
                // @Given("commented out")
                /* @When("also commented out") */
                String s = "@Then(\"a string\")";
                @Given("the real one")
                void s() {}
            }
        """.trimIndent()

        assertEquals(listOf("the real one"), JavaStepDefinitionScanner.scan(source).map { it.expression })
    }

    @Test
    fun `ignores unrelated and marker annotations`() {
        val source = """
            class S {
                @Override
                @SuppressWarnings("unchecked")
                void s() {}
            }
        """.trimIndent()

        assertEquals(emptyList<String>(), JavaStepDefinitionScanner.scan(source).map { it.expression })
    }

    @Test
    fun `a file without step annotations is skipped`() {
        assertEquals(emptyList<StepDefinitionEntry>(), JavaStepDefinitionScanner.scan("class S { void s() {} }"))
    }
}
