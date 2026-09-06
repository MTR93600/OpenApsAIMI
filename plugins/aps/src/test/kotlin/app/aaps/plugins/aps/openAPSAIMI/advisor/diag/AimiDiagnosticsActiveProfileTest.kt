package app.aaps.plugins.aps.openAPSAIMI.advisor.diag

import android.content.Context
import android.content.SharedPreferences
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.keys.interfaces.Preferences
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * The report must show the profile the loop runs, not only the profile editor's preferences.
 *
 * On the 2026-09-06 support package the `LocalProfile_isf_0` preference read 70 / 30 mg/dL per U
 * while the engine was running 120 / 50. The report carried only the preference, so the profile
 * looked wrong when it was right. An analysis was written on that wrong reading.
 */
class AimiDiagnosticsActiveProfileTest {

    private fun manager(): AimiDiagnosticsManager {
        val sharedPreferences = mockk<SharedPreferences>(relaxed = true)
        every { sharedPreferences.all } returns emptyMap()
        val context = mockk<Context>(relaxed = true)
        every { context.packageName } returns "app.aaps"
        every { context.getSharedPreferences(any(), any()) } returns sharedPreferences
        return AimiDiagnosticsManager(
            context = context,
            preferences = mockk<Preferences>(relaxed = true),
            logger = mockk<AAPSLogger>(relaxed = true),
        )
    }

    private fun profile(): Profile {
        val profile = mockk<Profile>(relaxed = true)
        every { profile.units } returns GlucoseUnit.MGDL
        every { profile.percentage } returns 100
        every { profile.timeshift } returns 0
        every { profile.getIsfsMgdlValues() } returns arrayOf(
            Profile.ProfileValue(0, 120.0),
            Profile.ProfileValue(11 * 3600, 50.0),
        )
        every { profile.getIcsValues() } returns arrayOf(Profile.ProfileValue(0, 7.0))
        every { profile.getBasalValues() } returns arrayOf(Profile.ProfileValue(0, 0.5))
        every { profile.getSingleTargetsMgdl() } returns arrayOf(Profile.ProfileValue(0, 115.0))
        return profile
    }

    @Test
    fun `the running profile is printed block by block`() {
        val report = manager().generateReport(
            userMessage = "test",
            activeProfile = profile(),
            activeProfileName = "AIMI running",
        )

        assertThat(report).contains("[ACTIVE PROFILE]")
        assertThat(report).contains("Name: AIMI running")
        assertThat(report).contains("ISF (mg/dL per U): 00:00 120.00, 11:00 50.00")
        assertThat(report).contains("IC (g per U): 00:00 7.00")
        assertThat(report).contains("Basal (U/h): 00:00 0.50")
        assertThat(report).contains("Target (mg/dL): 00:00 115.00")
    }

    /** A missing profile must be stated. An absent section would read as "nothing to report". */
    @Test
    fun `a missing profile is stated, not left out`() {
        val report = manager().generateReport(userMessage = "test")

        assertThat(report).contains("[ACTIVE PROFILE]")
        assertThat(report).contains("Not available when the report was built.")
    }

    /** The trap that produced the wrong reading: the editor keys must carry a warning. */
    @Test
    fun `the preference dump warns that the editor keys may differ`() {
        val report = manager().generateReport(userMessage = "test", activeProfile = profile())

        assertThat(report).contains("the LocalProfile_* keys below are the profile editor's content")
        assertThat(report.indexOf("[ACTIVE PROFILE]"))
            .isLessThan(report.indexOf("[AIMI PREFERENCES]"))
    }
}
