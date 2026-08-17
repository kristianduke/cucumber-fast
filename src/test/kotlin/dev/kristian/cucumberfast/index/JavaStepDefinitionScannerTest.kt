package dev.kristian.cucumberfast.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JavaStepDefinitionScannerTest {

    private fun steps(source: String) =
        JavaStepDefinitionScanner.scan(source).stepDefinitions.map { it.keyword to it.expression }

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

        assertEquals(listOf("Given" to "I have {int} cukes", "Then" to "^I am (.*)$"), steps(source))
    }

    @Test
    fun `offsets point at the annotation`() {
        val source = "class S {\n    @Given(\"a step\")\n    void s() {}\n}"
        val entry = JavaStepDefinitionScanner.scan(source).stepDefinitions.single()
        assertTrue(source.substring(entry.offset).startsWith("@Given"))
        assertEquals(StepDefinitionKind.ANNOTATION, entry.kind)
    }

    @Test
    fun `reads the named value attribute in any order`() {
        assertEquals(
            listOf("Given" to "a step"),
            steps("""class S { @Given(value = "a step", timeout = 1000) void s() {} }"""),
        )
        assertEquals(
            listOf("Given" to "a step"),
            steps("""class S { @Given(timeout = 1000, value = "a step") void s() {} }"""),
        )
    }

    @Test
    fun `reads fully qualified annotations`() {
        assertEquals(listOf("When" to "a step"), steps("""class S { @io.cucumber.java.en.When("a step") void s() {} }"""))
    }

    @Test
    fun `unescapes the pattern as the compiler would`() {
        val source = """class S { @Given("^I have (\\d+) cukes$") void s() {} }"""
        assertEquals("""^I have (\d+) cukes$""", JavaStepDefinitionScanner.scan(source).stepDefinitions.single().expression)
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

        assertEquals(listOf("Given" to "the real one"), steps(source))
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

        assertEquals(emptyList<Pair<String, String>>(), steps(source))
    }

    @Test
    fun `a file without step annotations is skipped`() {
        assertTrue(JavaStepDefinitionScanner.scan("class S { void s() {} }").isEmpty)
    }

    @Test
    fun `localized annotations are recognised through their import`() {
        val source = """
            import io.cucumber.java.de.Angenommen;
            import io.cucumber.java.de.Dann;

            public class CukeSteps {
                @Angenommen("ich habe {int} Gurken")
                public void ichHabeGurken(int anzahl) {}

                @Dann("bin ich satt")
                public void binIchSatt() {}
            }
        """.trimIndent()

        assertEquals(
            listOf("Angenommen" to "ich habe {int} Gurken", "Dann" to "bin ich satt"),
            steps(source),
        )
    }

    @Test
    fun `an annotation named like a keyword but imported from elsewhere is still indexed for later checking`() {
        // Resolution re-checks the package, so a false positive here costs a candidate, not a result.
        assertEquals(listOf("Given" to "a step"), steps("""class S { @Given("a step") void s() {} }"""))
    }

    @Test
    fun `lambda step definitions are found in java8 classes`() {
        val source = """
            import io.cucumber.java8.En;

            public class CukeSteps implements En {
                public CukeSteps() {
                    Given("I have {int} cukes", (Integer count) -> {});
                    Then("^I am (.*)$", (String mood) -> {});
                }
            }
        """.trimIndent()

        val entries = JavaStepDefinitionScanner.scan(source).stepDefinitions
        assertEquals(listOf("Given" to "I have {int} cukes", "Then" to "^I am (.*)$"), steps(source))
        assertTrue(entries.all { it.kind == StepDefinitionKind.LAMBDA })
        assertTrue(source.substring(entries[0].offset).startsWith("Given("))
    }

    @Test
    fun `a bare method call is not a step definition without the java8 import`() {
        val source = """
            public class Helper {
                void configure() {
                    When("this is just a method named like a keyword");
                }
            }
        """.trimIndent()

        assertEquals(emptyList<Pair<String, String>>(), steps(source))
    }

    @Test
    fun `parameter type declarations are found`() {
        val source = """
            import io.cucumber.java.ParameterType;

            public class Types {
                @ParameterType("red|blue|yellow")
                public Colour colour(String name) { return null; }

                @ParameterType(value = "\\d+ cukes", name = "cukes")
                public Integer cukeCount(String raw) { return null; }
            }
        """.trimIndent()

        assertEquals(
            listOf("colour" to "red|blue|yellow", "cukes" to """\d+ cukes"""),
            JavaStepDefinitionScanner.scan(source).parameterTypes.map { it.name to it.regex },
        )
    }
}
