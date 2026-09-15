package app.aaps.plugins.aps.openAPSAIMI.advisor

import app.aaps.core.data.model.EPS
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.ICfg
import app.aaps.core.data.model.data.Block
import app.aaps.core.data.model.data.TargetBlock
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import kotlin.time.Duration.Companion.hours

/**
 * Pins the fix for the `getBasal(timestamp)` / `getBasalTimeFromMidnight(seconds)` mixup in
 * `AimiAdvisorService`.
 *
 * `Profile.getBasal(timestamp: Long)` expects epoch millis. The old code called it with
 * `hour * 3600` (seconds, not millis) for every hour of the day - a value that never exceeds 82800,
 * i.e. always inside the first 83 seconds of 1 January 1970 UTC. Every hour therefore read the same
 * midnight block. `Profile.getBasalTimeFromMidnight(timeAsSeconds: Int)` is the API that actually
 * takes seconds from midnight, and is what the fixed code now calls.
 */
class AimiAdvisorServiceBasalTest : TestBase() {

    @Mock lateinit var profileFunction: ProfileFunction
    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var preferences: Preferences

    /** 1.0 U/h from 00:00, 2.0 U/h from 06:00 - a profile the midnight-block bug cannot fake. */
    private fun twoRateProfile(): ProfileSealed.EPS {
        val basalBlocks = listOf(
            Block(duration = 6.hours.inWholeMilliseconds, amount = 1.0),
            Block(duration = 18.hours.inWholeMilliseconds, amount = 2.0),
        )
        val flatIsf = listOf(Block(duration = 24.hours.inWholeMilliseconds, amount = 40.0))
        val flatIc = listOf(Block(duration = 24.hours.inWholeMilliseconds, amount = 10.0))
        val flatTarget = listOf(TargetBlock(duration = 24.hours.inWholeMilliseconds, lowTarget = 100.0, highTarget = 100.0))
        val eps = EPS(
            timestamp = 0L,
            basalBlocks = basalBlocks,
            isfBlocks = flatIsf,
            icBlocks = flatIc,
            targetBlocks = flatTarget,
            glucoseUnit = GlucoseUnit.MGDL,
            originalProfileName = "test",
            originalCustomizedName = "test",
            originalTimeshift = 0,
            originalPercentage = 100,
            originalDuration = 0,
            originalEnd = 0,
            iCfg = ICfg(insulinLabel = "Fake", insulinEndTime = 5 * 3600 * 1000L, insulinPeakTime = 75 * 60 * 1000L, concentration = 1.0),
        )
        return ProfileSealed.EPS(eps, activePlugin = null)
    }

    @Test
    fun total_basal_is_not_24x_the_midnight_rate() = runBlocking {
        whenever(profileFunction.getProfile()).thenReturn(twoRateProfile())
        whenever(persistenceLayer.getBgReadingsDataFromTimeToTime(any(), any(), any())).thenReturn(emptyList())
        val service = AimiAdvisorService(profileFunction = profileFunction, persistenceLayer = persistenceLayer, preferences = preferences)

        val context = service.collectContext(periodDays = 1)

        // Buggy: 24 * 1.0 (midnight rate for every hour) = 24.0. Fixed: 6h*1.0 + 18h*2.0 = 42.0.
        assertThat(context.profile.totalBasal).isEqualTo(42.0)
        // Buggy: profile.getBasal(0L) reads epoch 0 (midnight UTC) - happens to also be the 00:00
        // block here, but for the wrong reason; getBasalTimeFromMidnight(0) reads it directly.
        assertThat(context.profile.nightBasal).isEqualTo(1.0)
    }

    @Test
    fun basal_proposal_rows_use_their_own_hour_not_the_midnight_rate() = runBlocking {
        whenever(profileFunction.getProfile()).thenReturn(twoRateProfile())
        whenever(persistenceLayer.getBgReadingsDataFromTimeToTime(any(), any(), any())).thenReturn(emptyList())
        val service = AimiAdvisorService(profileFunction = profileFunction, persistenceLayer = persistenceLayer, preferences = preferences)

        val proposal = service.generateBasalProfileProposal(periodDays = 1)

        assertThat(proposal.rows).hasSize(24)
        assertThat(proposal.rows[0].current).isEqualTo(1.0)
        assertThat(proposal.rows[5].current).isEqualTo(1.0)
        assertThat(proposal.rows[6].current).isEqualTo(2.0)
        assertThat(proposal.rows[23].current).isEqualTo(2.0)
    }
}
