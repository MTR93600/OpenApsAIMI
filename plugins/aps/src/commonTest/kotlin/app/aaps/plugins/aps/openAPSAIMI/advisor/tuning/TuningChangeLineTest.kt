package app.aaps.plugins.aps.openAPSAIMI.advisor.tuning

import app.aaps.core.keys.DoubleKey
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The one line a tuning change shows the user.
 *
 * `formatChangeLine` builds the text the preview dialog and the applied summary both print, so the
 * step name in it is read. It used to be lower cased with `Locale.US`, the JVM way of saying "not
 * the device locale"; shared code says the same thing with the no-argument `lowercase()`. The input
 * is a [TuningStepTier] name, which is ASCII, so the text is unchanged - including on a Turkish
 * device, which is the only place the two could ever disagree.
 *
 * Study source set: [TuningContextApplySupport] moved to commonMain in this change and the format
 * helper needs nothing injected, so this lives in `commonTest` (`kotlin.test`) and runs on Native
 * too. Names are camelCase (no backtick commas).
 */
class TuningChangeLineTest {

    private fun lineFor(tier: TuningStepTier): String =
        TuningContextApplySupport.formatChangeLine(
            TuningChange(
                key = DoubleKey.OApsAIMIHighBGMaxSMB,
                labelKey = "high_bg_max_smb",
                oldValue = 1.0,
                newValue = 1.5,
                reason = "test",
                tier = tier,
            ),
        )

    @Test
    fun theStepNameIsLowerCased() {
        assertTrue(lineFor(TuningStepTier.MODERATE).endsWith("(moderate step)"), lineFor(TuningStepTier.MODERATE))
    }

    @Test
    fun everyStepNameIsLowerCased() {
        TuningStepTier.entries.forEach { tier ->
            val line = lineFor(tier)
            assertTrue(line.endsWith("(${tier.name.lowercase()} step)"), line)
        }
    }

    @Test
    fun theLineShowsTheOldAndTheNewValue() {
        val line = lineFor(TuningStepTier.MICRO)
        assertTrue(line.contains("→"), line)
    }
}
