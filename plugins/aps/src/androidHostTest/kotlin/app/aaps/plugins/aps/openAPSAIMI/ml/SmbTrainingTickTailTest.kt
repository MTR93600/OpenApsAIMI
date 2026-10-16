package app.aaps.plugins.aps.openAPSAIMI.ml

import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.DetermineBasalaimiSMB2
import app.aaps.plugins.aps.openAPSAIMI.advisor.gestation.GestationalAutopilot
import app.aaps.plugins.aps.openAPSAIMI.autodrive.AutodriveEngine
import app.aaps.plugins.aps.openAPSAIMI.basal.DynamicBasalController
import app.aaps.plugins.aps.openAPSAIMI.compose.AimiAutonomyMode
import app.aaps.plugins.aps.openAPSAIMI.compose.AimiBehaviorRuntimeProfile
import app.aaps.plugins.aps.openAPSAIMI.orchestration.AimiTickContext
import app.aaps.plugins.aps.openAPSAIMI.physio.AIMIInsulinDecisionAdapterMTR
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdLearnedState
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiAuditor
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiBehaviorProfileSource
import app.aaps.plugins.aps.openAPSAIMI.tpo.InMemoryAimiStorage
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
import app.aaps.plugins.aps.openAPSAIMI.validation.PumpCapabilityValidator
import app.aaps.plugins.aps.openAPSAIMI.wcycle.WCycleFacade
import app.aaps.plugins.aps.openAPSAIMI.wcycle.WCycleLearner
import app.aaps.plugins.aps.openAPSAIMI.wcycle.WCyclePreferences
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

/**
 * The tick tail writes one training row for every tick, whatever path the tick took.
 *
 * A tick on the Autodrive path, or one that returns early from a meal mode, never reaches the SMB
 * executor, so the executor never stages a row for it. These tests run the tail on such a tick: no
 * row was staged before, and the tail must stage one and label it with the dose that tick gave.
 */
class SmbTrainingTickTailTest {

    private val tickMs = 1_800_000_000_000L
    private val storage = InMemoryAimiStorage()
    private val csv = AimiPath("/aimi/oapsaimiML2_records.csv")

    private fun newTick(): DetermineBasalaimiSMB2 {
        val dateUtil = mock(DateUtil::class.java)
        whenever(dateUtil.now()).thenReturn(tickMs)
        whenever(dateUtil.dateAndTimeString(any())).thenReturn("10/04/2026 12:00")
        val behavior = mock(AimiBehaviorProfileSource::class.java)
        whenever(behavior.read(any())).thenReturn(AimiBehaviorRuntimeProfile(2, 2, 2, 2, AimiAutonomyMode.Observation))
        val tick = DetermineBasalaimiSMB2(
            profileUtil = mock(ProfileUtil::class.java),
            fabricPrivacy = mock(FabricPrivacy::class.java),
            preferences = mock(Preferences::class.java),
            pkPdLearnedState = mock(PkPdLearnedState::class.java),
            gestationalAutopilot = mock(GestationalAutopilot::class.java),
            auditorOrchestrator = mock(AimiAuditor::class.java),
            behaviorProfileSource = behavior,
            uiInteraction = mock(UiInteraction::class.java),
            notificationManager = mock(NotificationManager::class.java),
            wCycleFacade = mock(WCycleFacade::class.java),
            wCyclePreferences = mock(WCyclePreferences::class.java),
            wCycleLearner = mock(WCycleLearner::class.java),
            pumpCapabilityValidator = mock(PumpCapabilityValidator::class.java),
            dynamicBasalController = mock(DynamicBasalController::class.java),
            autodriveEngine = mock(AutodriveEngine::class.java),
            rh = mock(TextResolver::class.java),
        )
        set(tick, "dateUtil", dateUtil)
        set(tick, "storage", storage)
        set(tick, "aapsLogger", mock(AAPSLogger::class.java))
        set(tick, "physioAdapter", mock(AIMIInsulinDecisionAdapterMTR::class.java))
        set(tick, "bg", 180.0)
        set(tick, "smbTrainingRowTickKey", tickMs)
        return tick
    }

    private fun set(target: Any, name: String, value: Any?) {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }

    private fun runTail(tick: DetermineBasalaimiSMB2, nowMs: Long, result: RT?) {
        val ctx = mock(AimiTickContext::class.java)
        whenever(ctx.currentTime).thenReturn(nowMs)
        val method = tick.javaClass.declaredMethods.single { it.name == "stageAndFlushSmbTrainingRow" }
        method.isAccessible = true
        method.invoke(tick, ctx, result)
    }

    private fun labelsOfWrittenRows(): List<String> {
        val lines = storage.readLines(csv)
        val header = lines.first().split(", ")
        val labelIndex = header.indexOf("smbGiven")
        return lines.drop(1).map { it.split(",")[labelIndex] }
    }

    @Test
    fun `a tick that never reached the executor still gets a row labelled with its dose`() {
        val tick = newTick()
        // Tick one: Autodrive delivered 1.2 U and the SMB executor was skipped.
        runTail(tick, tickMs, RT(runningDynamicIsf = false, units = 1.2))
        // 46 minutes later the outcome window of that row has closed and the tail flushes it.
        runTail(tick, tickMs + 46 * 60_000L, null)

        assertThat(labelsOfWrittenRows()).containsExactly("1.2000")
    }

    @Test
    fun `a tick that ran and gave nothing is labelled zero`() {
        val tick = newTick()
        runTail(tick, tickMs, RT(runningDynamicIsf = false, units = null))
        runTail(tick, tickMs + 46 * 60_000L, null)

        assertThat(labelsOfWrittenRows()).containsExactly("0.0000")
    }

    @Test
    fun `a tick with no result is written with an empty label`() {
        val tick = newTick()
        runTail(tick, tickMs, null)
        runTail(tick, tickMs + 46 * 60_000L, null)

        assertThat(labelsOfWrittenRows()).containsExactly("")
    }

    @Test
    fun `the old corpus is moved aside once and never deleted`() {
        storage.files[csv.value] = "old header\nold row\n"
        val tick = newTick()
        runTail(tick, tickMs, RT(runningDynamicIsf = false, units = 0.5))
        runTail(tick, tickMs + 46 * 60_000L, null)
        runTail(tick, tickMs + 47 * 60_000L, null)

        val archives = storage.files.keys.filter { it.contains("oapsaimiML2_records_archive_") }
        assertThat(archives).hasSize(1)
        assertThat(storage.files[archives.single()]).isEqualTo("old header\nold row\n")
        assertThat(storage.files).containsKey("/aimi/oapsaimiML2_corpus_restart_v1")
        // The live file now holds only rows of the new kind.
        assertThat(storage.readLines(csv)).hasSize(2)
        assertThat(storage.readLines(csv)[1]).doesNotContain("old row")
    }
}
