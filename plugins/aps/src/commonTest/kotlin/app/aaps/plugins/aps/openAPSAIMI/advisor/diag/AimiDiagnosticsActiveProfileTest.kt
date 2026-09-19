package app.aaps.plugins.aps.openAPSAIMI.advisor.diag

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.profile.Profile
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The report must show the profile the loop runs, not only the profile editor's preferences.
 *
 * On the 2026-09-06 support package the `LocalProfile_isf_0` preference read 70 / 30 mg/dL per U
 * while the engine was running 120 / 50. The report carried only the preference, so the profile
 * looked wrong when it was right. An analysis was written on that wrong reading.
 *
 * Matching tests from `origin/dev_OAPSAIMI` @ `02c90656b1` (`AimiDiagnosticsActiveProfileTest`).
 * Study source set: the [ACTIVE PROFILE] section is commonMain with no Android, so the tests live
 * in `commonTest` (`kotlin.test`) — same layout as [app.aaps.plugins.aps.openAPSAIMI.ml.SmbTrainingRowBufferTest].
 * Assertions are the same locks as the Truth/JUnit reference.
 */
class AimiDiagnosticsActiveProfileTest {

    private fun runningBlocks(): AimiDiagnosticsActiveProfile.Blocks =
        AimiDiagnosticsActiveProfile.Blocks(
            name = "AIMI running",
            units = GlucoseUnit.MGDL,
            percentage = 100,
            timeshift = 0,
            isf = listOf(
                Profile.ProfileValue(0, 120.0),
                Profile.ProfileValue(11 * 3600, 50.0),
            ),
            ic = listOf(Profile.ProfileValue(0, 7.0)),
            basal = listOf(Profile.ProfileValue(0, 0.5)),
            target = listOf(Profile.ProfileValue(0, 115.0)),
        )

    private fun report(blocks: AimiDiagnosticsActiveProfile.Blocks?): String {
        val sb = StringBuilder()
        AimiDiagnosticsActiveProfile.writeSection(sb, blocks)
        AimiDiagnosticsActiveProfile.writePreferencesPreamble(sb)
        return sb.toString()
    }

    @Test
    fun `the running profile is printed block by block`() {
        val text = report(runningBlocks())

        assertTrue("[ACTIVE PROFILE]" in text)
        assertTrue("Name: AIMI running" in text)
        assertTrue("ISF (mg/dL per U): 00:00 120.00, 11:00 50.00" in text)
        assertTrue("IC (g per U): 00:00 7.00" in text)
        assertTrue("Basal (U/h): 00:00 0.50" in text)
        assertTrue("Target (mg/dL): 00:00 115.00" in text)
    }

    /** A missing profile must be stated. An absent section would read as "nothing to report". */
    @Test
    fun `a missing profile is stated not left out`() {
        val text = report(null)

        assertTrue("[ACTIVE PROFILE]" in text)
        assertTrue("Not available when the report was built." in text)
    }

    /** The trap that produced the wrong reading: the editor keys must carry a warning. */
    @Test
    fun `the preference dump warns that the editor keys may differ`() {
        val text = report(runningBlocks())

        assertTrue("the LocalProfile_* keys below are the profile editor's content" in text)
        assertTrue(text.indexOf("[ACTIVE PROFILE]") < text.indexOf("[AIMI PREFERENCES]"))
    }
}
