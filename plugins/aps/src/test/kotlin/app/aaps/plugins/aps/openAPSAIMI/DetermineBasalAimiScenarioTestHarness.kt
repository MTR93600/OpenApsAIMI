package app.aaps.plugins.aps.openAPSAIMI

import android.os.Environment
import androidx.collection.LongSparseArray
import app.aaps.core.data.model.TDD
import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.stats.TirCalculator
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.autodrive.AutodriveEngine
import app.aaps.plugins.aps.openAPSAIMI.autodrive.safety.AutoDriveGater
import app.aaps.plugins.aps.openAPSAIMI.basal.DynamicBasalController
import app.aaps.plugins.aps.openAPSAIMI.learning.BasalMlTrainingCoordinator
import app.aaps.plugins.aps.openAPSAIMI.learning.BasalNeuralLearner
import app.aaps.plugins.aps.openAPSAIMI.physio.AIMIInsulinDecisionAdapterMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.HealthContextSnapshot
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryGuard
import app.aaps.plugins.aps.openAPSAIMI.wcycle.WCycleFacade
import app.aaps.plugins.aps.openAPSAIMI.wcycle.WCycleLearner
import app.aaps.plugins.aps.openAPSAIMI.wcycle.WCyclePreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import java.io.File
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdLearnedState

/**
 * Shared construction for moteur AIMI + persistance / stats stubs (scénarios `logDecisionFinal`).
 */
internal class DetermineBasalAimiScenarioTestHarness(
    private val now: Long,
    private val preferences: Preferences,
    val trajectoryGuard: TrajectoryGuard,
    val autodriveGater: AutoDriveGater,
    val autodriveEngine: AutodriveEngine,
    val physioAdapter: AIMIInsulinDecisionAdapterMTR,
    val basalNeuralLearner: BasalNeuralLearner,
) {
    val persistenceLayer = mockk<PersistenceLayer>(relaxed = true)
    val engine: DetermineBasalaimiSMB2

    init {
        mockkStatic(Environment::class)
        val mockFile = mockk<File>(relaxed = true)
        every { Environment.getExternalStorageDirectory() } returns mockFile
        every { Environment.getExternalStoragePublicDirectory(any()) } returns mockFile
        every { mockFile.absolutePath } returns "/tmp"
        // Real temp dir so the engine's file-path lazies (appExternalFallbackDir, exporter dirs) don't NPE on a mock
        // File with a null path — the tick must reach its decision branches, not abort into a safe-hold.
        val realTmpDir = File(System.getProperty("java.io.tmpdir"))

        coEvery { persistenceLayer.getUserEntryDataFromTime(any()) } returns emptyList()
        coEvery { persistenceLayer.getBolusesFromTime(any(), any()) } returns emptyList()
        coEvery { persistenceLayer.getCarbsFromTime(any(), any()) } returns emptyList()
        coEvery { persistenceLayer.getTherapyEventDataFromToTime(any(), any()) } returns emptyList()
        coEvery { persistenceLayer.getBgReadingsDataFromTime(any(), any()) } returns emptyList()
        coEvery { persistenceLayer.getTemporaryBasalsStartingFromTime(any(), any()) } returns emptyList()
        coEvery { persistenceLayer.getTherapyEventDataFromTime(any(), any<TE.Type>(), any()) } returns emptyList()
        coEvery { persistenceLayer.getTherapyEventDataFromTime(any(), any()) } returns emptyList()
        coEvery { persistenceLayer.getTherapyEventDataFromTime(any(), true) } returns emptyList()
        coEvery { persistenceLayer.getFutureCob() } returns 0.0
        coEvery { persistenceLayer.getMostRecentCarbByDate() } returns null
        coEvery { persistenceLayer.getMostRecentCarbAmount() } returns null

        val tddCalculator = mockk<TddCalculator>(relaxed = true)
        coEvery { tddCalculator.calculate(1L, false) } returns LongSparseArray<TDD>().apply {
            put(1L, TDD(timestamp = now, totalAmount = 48.0))
        }
        coEvery { tddCalculator.calculateDaily(-24, 0) } returns TDD(timestamp = now, totalAmount = 48.0)

        val tirCalculator = mockk<TirCalculator>(relaxed = true)
        coEvery { tirCalculator.calculate(1L, 65.0, 180.0) } returns LongSparseArray()

        every { trajectoryGuard.getLastAnalysis() } returns null
        every { physioAdapter.getLatestSnapshot() } returns HealthContextSnapshot()
        every { physioAdapter.getRealTimeActivity() } returns AIMIInsulinDecisionAdapterMTR.RealTimeActivity(0, 0)

        engine = DetermineBasalaimiSMB2(
            profileUtil = mockk(relaxed = true),
            fabricPrivacy = mockk(relaxed = true),
            preferences = preferences,
            pkPdLearnedState = PkPdLearnedState(),
            gestationalAutopilot = mockk(relaxed = true),
            auditorOrchestrator = mockk(relaxed = true),
            uiInteraction = mockk(relaxed = true),
            notificationManager = mockk<NotificationManager>(relaxed = true),
            wCycleFacade = mockk<WCycleFacade>(relaxed = true),
            wCyclePreferences = mockk<WCyclePreferences>(relaxed = true),
            wCycleLearner = mockk<WCycleLearner>(relaxed = true),
            pumpCapabilityValidator = mockk(relaxed = true),
            dynamicBasalController = mockk<DynamicBasalController>(relaxed = true),
            autodriveEngine = autodriveEngine,
            context = mockk(relaxed = true) {
                every { getExternalFilesDir(any()) } returns realTmpDir
            }
        ).apply {
            this.persistenceLayer = this@DetermineBasalAimiScenarioTestHarness.persistenceLayer
            this.tddCalculator = tddCalculator
            this.tirCalculator = tirCalculator
            dateUtil = mockk(relaxed = true) {
                every { now() } returns now
            }
            profileFunction = mockk(relaxed = true)
            iobCobCalculator = mockk(relaxed = true)
            aimiLogger = mockk(relaxed = true)
            activePlugin = mockk(relaxed = true)
            basalDecisionEngine = mockk(relaxed = true)
            this.autodriveGater = this@DetermineBasalAimiScenarioTestHarness.autodriveGater
            activityManager = mockk(relaxed = true)
            glucoseStatusCalculatorAimi = mockk(relaxed = true)
            comparator = mockk(relaxed = true)
            basalLearner = mockk(relaxed = true)
            unifiedReactivityLearner = mockk(relaxed = true)
            basalNeuralLearner = this@DetermineBasalAimiScenarioTestHarness.basalNeuralLearner
            basalMlTrainingCoordinator = mockk(relaxed = true)
            storageHelper = mockk(relaxed = true)
            aapsLogger = mockk(relaxed = true)
            trajectoryGuard = this@DetermineBasalAimiScenarioTestHarness.trajectoryGuard
            trajectoryHistoryProvider = mockk(relaxed = true)
            contextManager = mockk(relaxed = true)
            contextInfluenceEngine = mockk(relaxed = true)
            physioAdapter = this@DetermineBasalAimiScenarioTestHarness.physioAdapter
            continuousStateEstimator = mockk(relaxed = true) {
                every { getLastRa() } returns 0.0
            }
        }
    }
}
