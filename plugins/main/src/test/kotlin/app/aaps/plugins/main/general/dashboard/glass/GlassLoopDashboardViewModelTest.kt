package app.aaps.plugins.main.general.dashboard.glass

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.aps.APS
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.stats.TIR
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.stats.TirCalculator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.GlucoseStatusCalculatorAimi
import app.aaps.plugins.aps.openAPSAIMI.learning.BasalNeuralLearner
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class GlassLoopDashboardViewModelTest {

    private val activePlugin: ActivePlugin = mock()
    private val glucoseStatusProvider: GlucoseStatusProvider = mock()
    private val tddCalculator: TddCalculator = mock()
    private val tirCalculator: TirCalculator = mock()
    private val glucoseStatusCalculatorAimi: GlucoseStatusCalculatorAimi = mock()
    private val persistenceLayer: PersistenceLayer = mock()
    private val profileFunction: ProfileFunction = mock()
    private val profileUtil: ProfileUtil = mock()
    private val preferences: Preferences = mock()
    private val resourceHelper: ResourceHelper = mock()
    private val dateUtil: DateUtil = mock()
    private val config: Config = mock()
    private val basalNeuralLearner: BasalNeuralLearner = mock()
    private val aimiStorageHelper: AimiStorageHelper = mock()

    // Held so tests can advance the ViewModel's Main-dispatched refresh() coroutine.
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var viewModel: GlassLoopDashboardViewModel

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        whenever(resourceHelper.gs(any(), any())).thenReturn("formatted")
        // isfText's format resource call passes 2 vararg args (value + unit label) -- Mockito matches
        // vararg stubs by argument count, so the 1-arg stub above does not cover it; without this, the
        // real-lastAPSResult test's isfText resolves to null and GlassLoopDashboardState's non-null
        // constructor throws.
        whenever(resourceHelper.gs(any(), any(), any())).thenReturn("formatted")
        whenever(resourceHelper.gs(any())).thenReturn("unit")
        // targetUnits defaults to profileUtil.units, which is null on an unstubbed mock -> match with anyOrNull().
        whenever(profileUtil.fromMgdlToStringInUnits(anyOrNull(), anyOrNull())).thenReturn("120")
        whenever(profileUtil.fromMgdlToSignedStringInUnits(anyOrNull(), anyOrNull())).thenReturn("+1.0")
        whenever(profileUtil.fromMgdlToUnits(anyOrNull(), anyOrNull())).thenReturn(40.0)
        whenever(profileFunction.getUnits()).thenReturn(GlucoseUnit.MGDL)
        whenever(config.VERSION_NAME).thenReturn("3.5.0")
        whenever(dateUtil.now()).thenReturn(1_000_000_000L)
        whenever(dateUtil.timeString(any())).thenReturn("14:32")
        whenever(dateUtil.dateAndTimeString(any())).thenReturn("2026-09-11 14:32")
        // getGovernanceSnapshot()/getAimiDirectory() return non-null Kotlin types -- Mockito's unstubbed
        // default (null) would trip a Kotlin null-check at the call site, so both need an explicit stub.
        whenever(basalNeuralLearner.getGovernanceSnapshot()).thenReturn(mock())
        whenever(aimiStorageHelper.getAimiDirectory()).thenReturn(File("/tmp/glass-loop-dashboard-test"))

        val emptyTir: TIR = mock()
        whenever(emptyTir.belowPct()).thenReturn(null)
        whenever(tirCalculator.calculateHour(70.0, 180.0)).thenReturn(mock())
        whenever(tirCalculator.calculateDaily(70.0, 180.0)).thenReturn(mock())
        whenever(tirCalculator.averageTIR(any())).thenReturn(emptyTir)
        whenever(glucoseStatusCalculatorAimi.getAimiFeatures(true)).thenReturn(null)
        runBlocking {
            whenever(persistenceLayer.getLastStepsCountFromTimeToTime(any(), any())).thenReturn(null)
            whenever(tddCalculator.calculate(7L, allowMissingDays = true)).thenReturn(null)
        }

        viewModel = GlassLoopDashboardViewModel(
            activePlugin, glucoseStatusProvider, tddCalculator, tirCalculator, glucoseStatusCalculatorAimi,
            persistenceLayer, profileFunction, profileUtil, preferences, resourceHelper, dateUtil, config,
            basalNeuralLearner, aimiStorageHelper,
        )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // refresh() hops onto the real Dispatchers.IO inside its viewModelScope.launch, so the
    // continuation that writes the final state is posted back to testDispatcher from a real
    // background thread, not from the test thread. A single advanceUntilIdle() right after
    // refresh() can run before that continuation has been posted. The default GlassLoopDashboardState
    // already has isLoading = false, so we cannot just wait for "isLoading == false" -- we must first
    // observe it flip to true (the coroutine's synchronous first update actually ran) and only then
    // wait for it to flip back to false (the coroutine's real completion), each bounded by a timeout.
    private fun awaitRefreshComplete(timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!viewModel.uiState.value.isLoading) {
            testDispatcher.scheduler.advanceUntilIdle()
            if (viewModel.uiState.value.isLoading) break
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for refresh() to start" }
            Thread.sleep(2)
        }
        while (viewModel.uiState.value.isLoading) {
            testDispatcher.scheduler.advanceUntilIdle()
            if (!viewModel.uiState.value.isLoading) break
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for refresh() to complete" }
            Thread.sleep(5)
        }
    }

    @Test
    fun `a null lastAPSResult produces a state with defaulted text, no crash`() {
        whenever(activePlugin.activeAPS).thenReturn(null)
        whenever(glucoseStatusProvider.glucoseStatusData).thenReturn(null)

        viewModel.refresh()
        awaitRefreshComplete()

        val state = viewModel.uiState.value
        assertThat(state.isLoading).isFalse()
        assertThat(state.lastRunTime).isEmpty()
        assertThat(state.buildVersionText).isEqualTo("3.5.0")
        assertThat(state.glucoseText).isEqualTo("--")
        assertThat(state.targetBgText).isEqualTo("--")
        assertThat(state.isfText).isEqualTo("--")
        assertThat(state.iobText).isEqualTo("--")
        assertThat(state.maxIobProfileText).isEqualTo("--")
        assertThat(state.requestedSmbText).isEqualTo("--")
    }

    @Test
    fun `a real lastAPSResult with date greater than zero produces a non-empty lastRunTime`() {
        val apsResult: APSResult = mock()
        whenever(apsResult.date).thenReturn(1_000_000L)
        whenever(apsResult.smb).thenReturn(0.5)
        whenever(apsResult.targetBG).thenReturn(100.0)
        whenever(apsResult.variableSens).thenReturn(40.0)
        val iobTotal = IobTotal(time = 0L, iob = 0.3, basaliob = 0.2)
        whenever(apsResult.iob).thenReturn(iobTotal)
        val oapsProfile: OapsProfileAimi = mock()
        whenever(oapsProfile.max_iob).thenReturn(3.5)
        whenever(apsResult.oapsProfileAimi).thenReturn(oapsProfile)
        val aps: APS = mock()
        whenever(aps.lastAPSResult).thenReturn(apsResult)
        whenever(activePlugin.activeAPS).thenReturn(aps)

        val glucoseStatus: GlucoseStatus = mock()
        whenever(glucoseStatus.glucose).thenReturn(120.0)
        whenever(glucoseStatus.delta).thenReturn(2.0)
        whenever(glucoseStatus.shortAvgDelta).thenReturn(1.5)
        whenever(glucoseStatus.longAvgDelta).thenReturn(1.0)
        whenever(glucoseStatusProvider.glucoseStatusData).thenReturn(glucoseStatus)

        viewModel.refresh()
        awaitRefreshComplete()

        val state = viewModel.uiState.value
        assertThat(state.lastRunTime).isEqualTo("14:32")
        assertThat(state.delta5mIsPositive).isTrue()
    }
}
