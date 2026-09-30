package app.aaps.plugins.aps.openAPSAIMI.hormonitor.viewer

import app.aaps.plugins.aps.openAPSAIMI.aimiDeviceLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The text the study viewer puts on screen for a raw label code.
 *
 * `HormonitorLabels.humanize` is the only thing between a study code and a `Text(...)` in
 * `HormonitorViewerScreen`, so everything it returns is read by a user. Two things moved when the
 * file went to shared code and both are pinned here: the map lookup, which used to upper case in
 * `Locale.US`, and the fallback prettifier, which used to case in the device locale.
 *
 * The prettifier also runs a regular expression with a look behind. That is worth a test of its own
 * on this source set: `commonTest` runs on Kotlin/Native, whose regular expression engine is not the
 * JVM's, and a look behind that quietly stopped matching would turn every camel case code into one
 * run-on word without anything failing.
 *
 * Study source set: [HormonitorLabels] moved to commonMain in this change, so these live in
 * `commonTest` (`kotlin.test`) and run on Native too. Names are camelCase (no backtick commas).
 */
class HormonitorLabelsTest {

    /**
     * Whether the machine running the tests asks for French.
     *
     * The curated wordings are chosen by the device language, so a test that hard coded the English
     * one would pass in London and fail in Paris. It is not a knob the test can turn - that is the
     * point of [aimiDeviceLanguage] - so the test reads it and expects the matching wording.
     */
    private val french = aimiDeviceLanguage().equals("fr", ignoreCase = true)

    private fun expected(english: String, frenchText: String) = if (french) frenchText else english

    @Test
    fun aKnownCodeGetsItsCuratedWording() {
        assertEquals(expected("First meal wave", "Première vague (repas)"), HormonitorLabels.humanize("FIRST_WAVE"))
    }

    @Test
    fun aKnownCodeIsFoundThroughTheUpperCaseFallback() {
        // The map is keyed on `FAST_MEAL`; a lower case code has to find it through `uppercase()`.
        assertEquals(expected("Fast meal", "Repas rapide"), HormonitorLabels.humanize("fast_meal"))
    }

    @Test
    fun anUnknownUnderscoreCodeIsPrettified() {
        assertEquals("Some unknown code", HormonitorLabels.humanize("SOME_UNKNOWN_CODE"))
    }

    @Test
    fun anUnknownCamelCaseCodeIsSplitOnItsBoundaries() {
        // This is the look behind regular expression. On a broken engine the answer would be
        // "Somecamelcasecode".
        assertEquals("Some camel case code", HormonitorLabels.humanize("someCamelCaseCode"))
    }

    @Test
    fun anUnknownCodeWithACapitalIKeepsTheDottedLetter() {
        // The casing is locale independent, so this reads the same on a Turkish device, where a
        // device-locale lowercase would have produced "Prıor state" with a dotless letter.
        assertEquals("Prior state", HormonitorLabels.humanize("PRIOR_STATE"))
    }

    @Test
    fun aKnownSlashCodeGetsItsCuratedWording() {
        assertEquals(expected("Stress / activity", "Stress / activité"), HormonitorLabels.humanize("Stress/Activity"))
    }

    @Test
    fun anUnknownSlashCodeIsSplitOnTheSlash() {
        assertEquals("Rest walk", HormonitorLabels.humanize("REST/WALK"))
    }

    @Test
    fun anEmptyCodeIsHandedBackUnchanged() {
        assertEquals("   ", HormonitorLabels.humanize("   "))
    }

    @Test
    fun theDeviceLanguageIsReallyAnswered() {
        // Guards the platform halves of `aimiDeviceLanguage`. A stub that returned an empty string
        // would leave every user on the English wording with nothing looking broken, which is the
        // exact failure this port is meant not to introduce.
        val language = aimiDeviceLanguage()
        assertTrue(language.isNotBlank(), "the platform answered no language at all")
        assertTrue(language.length in 2..3, "not an ISO 639 language code: $language")
    }
}
