package app.aaps.plugins.aps.openAPSAIMI.benchmarks

import android.os.Environment
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import app.aaps.plugins.aps.openAPSAIMI.DetermineBasalaimiSMB2
import io.mockk.*
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.aps.*
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.notifications.NotificationManager
import com.google.common.truth.Truth.assertThat
import kotlin.system.measureTimeMillis
import app.aaps.plugins.aps.openAPSAIMI.wcycle.WCycleFacade
import app.aaps.plugins.aps.openAPSAIMI.wcycle.WCyclePreferences
import app.aaps.plugins.aps.openAPSAIMI.wcycle.WCycleLearner
import app.aaps.plugins.aps.openAPSAIMI.basal.DynamicBasalController
import app.aaps.plugins.aps.openAPSAIMI.autodrive.AutodriveEngine
import io.mockk.coEvery
import java.io.File
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdLearnedState

class EngineBenchmarks {

    private lateinit var engine: DetermineBasalaimiSMB2
    private val persistenceLayer = mockk<PersistenceLayer>(relaxed = true)

    @BeforeEach
    fun setup() {
        mockkStatic(Environment::class)
        val mockFile = mockk<File>(relaxed = true)
        every { Environment.getExternalStorageDirectory() } returns mockFile
        every { Environment.getExternalStoragePublicDirectory(any()) } returns mockFile
        every { mockFile.absolutePath } returns "/tmp"

        // Suspend PersistenceLayer stubs
        coEvery { persistenceLayer.getUserEntryDataFromTime(any()) } returns emptyList()
        coEvery { persistenceLayer.getBolusesFromTime(any(), any()) } returns emptyList()
        coEvery { persistenceLayer.getCarbsFromTime(any(), any()) } returns emptyList()
        coEvery { persistenceLayer.getTherapyEventDataFromTime(any(), any()) } returns emptyList()
        coEvery { persistenceLayer.getTherapyEventDataFromTime(any(), any(), any()) } returns emptyList()
        coEvery { persistenceLayer.getTherapyEventDataFromToTime(any(), any()) } returns emptyList()
        coEvery { persistenceLayer.getBgReadingsDataFromTime(any(), any()) } returns emptyList()

        engine = DetermineBasalaimiSMB2(
            profileUtil = mockk(relaxed = true),
            fabricPrivacy = mockk(relaxed = true),
            preferences = mockk(relaxed = true),
            pkPdLearnedState = PkPdLearnedState(),
            gestationalAutopilot = mockk(relaxed = true),
            auditorOrchestrator = mockk(relaxed = true),
            uiInteraction = mockk(relaxed = true),
            notificationManager = mockk<NotificationManager>(relaxed = true),
            wCycleFacade = mockk(relaxed = true),
            wCyclePreferences = mockk(relaxed = true),
            wCycleLearner = mockk(relaxed = true),
            pumpCapabilityValidator = mockk(relaxed = true),
            dynamicBasalController = mockk(relaxed = true),
            autodriveEngine = mockk(relaxed = true),
            context = mockk(relaxed = true)
        ).apply {
            this.persistenceLayer = this@EngineBenchmarks.persistenceLayer
            tddCalculator = mockk(relaxed = true)
            tirCalculator = mockk(relaxed = true)
            dateUtil = mockk(relaxed = true)
            profileFunction = mockk(relaxed = true)
            iobCobCalculator = mockk(relaxed = true)
            activePlugin = mockk(relaxed = true)
            basalDecisionEngine = mockk(relaxed = true)
            autodriveGater = mockk(relaxed = true)
            activityManager = mockk(relaxed = true)
            glucoseStatusCalculatorAimi = mockk(relaxed = true)
            comparator = mockk(relaxed = true)
            basalLearner = mockk(relaxed = true)
            unifiedReactivityLearner = mockk(relaxed = true)
            basalNeuralLearner = mockk(relaxed = true)
            storageHelper = mockk(relaxed = true)
            aapsLogger = mockk(relaxed = true)
            trajectoryGuard = mockk(relaxed = true)
            trajectoryHistoryProvider = mockk(relaxed = true)
            contextManager = mockk(relaxed = true)
            contextInfluenceEngine = mockk(relaxed = true)
            physioAdapter = mockk(relaxed = true)
            continuousStateEstimator = mockk(relaxed = true)
        }
    }

    @Test
    fun `benchmark determine_basal execution time`() {
        val iterations = 50
        val warmup = 5
        
        repeat(warmup) { runSample() }
        
        val totalTime = measureTimeMillis {
            repeat(iterations) { runSample() }
        }
        
        val avgTime = totalTime.toDouble() / iterations
        println("🚀 Average execution time: $avgTime ms")
        assertThat(avgTime).isLessThan(100.0)
    }

    private fun runSample() {
        engine.determine_basal(
            glucose_status = mockk(relaxed = true),
            currenttemp = mockk(relaxed = true),
            iob_data_array = arrayOf(mockk(relaxed = true)),
            profile = mockk(relaxed = true),
            autosens_data = mockk(relaxed = true),
            mealData = mockk(relaxed = true),
            microBolusAllowed = true,
            currentTime = System.currentTimeMillis(),
            flatBGsDetected = false,
            dynIsfMode = false,
            uiInteraction = mockk(relaxed = true),
            extraDebug = "Benchmark Run"
        )
    }
}
