package dev.kristian.cucumberfast.index

import org.junit.Assert.assertEquals
import org.junit.Test

class GherkinStepScannerTest {

    @Test
    fun `scans the steps of a scenario`() {
        val feature = """
            Feature: Cukes
              Scenario: Eating
                Given I have 42 cukes in my belly
                When I wait 1 hour
                Then I am hungry
        """.trimIndent()

        assertEquals(
            listOf("I have 42 cukes in my belly", "I wait 1 hour", "I am hungry"),
            GherkinStepScanner.scan(feature).map { it.text },
        )
    }

    @Test
    fun `offsets point at the step text`() {
        val feature = "Feature: F\n  Scenario: S\n    Given I have cukes\n"
        val step = GherkinStepScanner.scan(feature).single()
        assertEquals("I have cukes", feature.substring(step.offset, step.offset + step.text.length))
    }

    @Test
    fun `comments and doc strings are not steps`() {
        val feature = """
            Feature: F
              Scenario: S
                # Given this is a comment
                Given a payload
                  ""${'"'}
                  When this line is inside a doc string
                  ""${'"'}
                Then it is stored
        """.trimIndent()

        assertEquals(listOf("a payload", "it is stored"), GherkinStepScanner.scan(feature).map { it.text })
    }

    @Test
    fun `bullet steps and conjunctions are steps`() {
        val feature = "Feature: F\n  Scenario: S\n    * a bullet step\n    And another\n    But not this one\n"
        assertEquals(
            listOf("a bullet step", "another", "not this one"),
            GherkinStepScanner.scan(feature).map { it.text },
        )
    }

    @Test
    fun `keywords need a separator and a body`() {
        val feature = "Feature: F\n  Scenario: S\n    Givenish text\n    Given\n    Whenever\n"
        assertEquals(emptyList<String>(), GherkinStepScanner.scan(feature).map { it.text })
    }
}
