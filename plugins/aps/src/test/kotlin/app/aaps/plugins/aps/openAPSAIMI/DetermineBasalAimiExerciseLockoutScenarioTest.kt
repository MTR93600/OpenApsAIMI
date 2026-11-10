package app.aaps.plugins.aps.openAPSAIMI

import android.os.Environment
import androidx.collection.LongSparseArray
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TDD
import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.stats.TirCalculator
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.autodrive.AutodriveEngine
import app.aaps.plugins.aps.openAPSAIMI.basal.DynamicBasalController
import app.aaps.plugins.aps.openAPSAIMI.learning.BasalNeuralLearner
import app.aaps.plugins.aps.openAPSAIMI.wcycle.WCycleFacade
import app.aaps.plugins.aps.openAPSAIMI.wcycle.WCycleLearner
import app.aaps.plugins.aps.openAPSAIMI.wcycle.WCyclePreferences
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdLearnedState

/**
 * Phase 2 P1 : branche `EXERCISE_LOCKOUT` (sans T3c) → [logDecisionFinal] → `updateLearning` une fois.
 * Aucune modification du code moteur ; régression détectée si le chemin ou les effets de bord changent.
 */
class DetermineBasalAimiExerciseLockoutScenarioTest {

    private lateinit var engine: DetermineBasalaimiSMB2
    private lateinit var preferences: Preferences
    private lateinit var basalNeuralLearner: BasalNeuralLearner
    private val persistenceLayer = mockk<PersistenceLayer>(relaxed = true)

    @BeforeEach
    fun setup() {
        mockkStatic(Environment::class)
        val mockFile = mockk<File>(relaxed = true)
        every { Environment.getExternalStorageDirectory() } returns mockFile
        every { Environment.getExternalStoragePublicDirectory(any()) } returns mockFile
        every { mockFile.absolutePath } returns "/tmp"

        coEvery { persistenceLayer.getUserEntryDataFromTime(any()) } returns emptyList()
        coEvery { persistenceLayer.getBolusesFromTime(any(), any()) } returns emptyList()
        coEvery { persistenceLayer.getCarbsFromTime(any(), any()) } returns emptyList()
        coEvery { persistenceLayer.getTherapyEventDataFromToTime(any(), any()) } returns emptyList()
        coEvery { persistenceLayer.getBgReadingsDataFromTime(any(), any()) } returns emptyList()
        coEvery { persistenceLayer.getTemporaryBasalsStartingFromTime(any(), any()) } returns emptyList()
        coEvery { persistenceLayer.getTherapyEventDataFromTime(any(), any<TE.Type>(), any()) } returns emptyList()
        coEvery { persistenceLayer.getFutureCob() } returns 0.0
        coEvery { persistenceLayer.getMostRecentCarbByDate() } returns null
        coEvery { persistenceLayer.getMostRecentCarbAmount() } returns null

        val now = System.currentTimeMillis()
        val sportEvent = TE(
            timestamp = now - 120_000L,
            duration = 3_600_000L,
            type = TE.Type.NOTE,
            note = "Sport test lockout",
            glucoseUnit = GlucoseUnit.MGDL
        )
        coEvery { persistenceLayer.getTherapyEventDataFromTime(any(), true) } returns listOf(sportEvent)

        val tddCalculator = mockk<TddCalculator>(relaxed = true)
        coEvery { tddCalculator.calculate(1L, false) } returns LongSparseArray<TDD>().apply {
            put(1L, TDD(timestamp = now, totalAmount = 48.0))
        }
        coEvery { tddCalculator.calculateDaily(-24, 0) } returns TDD(timestamp = now, totalAmount = 48.0)

        val tirCalculator = mockk<TirCalculator>(relaxed = true)
        coEvery { tirCalculator.calculate(1L, 65.0, 180.0) } returns LongSparseArray()

        preferences = mockk(relaxed = true)
        every { preferences.get(BooleanKey.OApsAIMIT3cBrittleMode) } returns false
        every { preferences.get(BooleanKey.AimiPhysioAssistantEnable) } returns false
        every { preferences.get(BooleanKey.OApsAIMIUnifiedReactivityEnabled) } returns false

        basalNeuralLearner = mockk(relaxed = true)

        engine = DetermineBasalaimiSMB2(
            profileUtil = mockk(relaxed = true),
            fabricPrivacy = mockk(relaxed = true),
            preferences = preferences,
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
            this.persistenceLayer = this@DetermineBasalAimiExerciseLockoutScenarioTest.persistenceLayer
            this.tddCalculator = tddCalculator
            this.tirCalculator = tirCalculator
            dateUtil = mockk(relaxed = true) {
                every { now() } returns now
            }
            profileFunction = mockk(relaxed = true)
            iobCobCalculator = mockk(relaxed = true)
            activePlugin = mockk(relaxed = true)
            basalDecisionEngine = mockk(relaxed = true)
            autodriveGater = mockk(relaxed = true)
            activityManager = mockk(relaxed = true)
            glucoseStatusCalculatorAimi = mockk(relaxed = true) {
                every { compute(true) } returns GlucoseStatusCalculatorAimi.Result(
                    gs = GlucoseStatusAIMI(glucose = 100.0, delta = 0.0, shortAvgDelta = 0.0, longAvgDelta = 0.0, date = now),
                    features = null,
                )
                every { getRecentGlucose() } returns listOf(100f, 100f, 100f)
            }
            comparator = mockk(relaxed = true)
            basalLearner = mockk(relaxed = true)
            unifiedReactivityLearner = mockk(relaxed = true)
            basalNeuralLearner = this@DetermineBasalAimiExerciseLockoutScenarioTest.basalNeuralLearner
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
    fun `exercise lockout without T3c calls updateLearning once via logDecisionFinal`() {
        val now = System.currentTimeMillis()
        val rt = engine.determine_basal(
            glucose_status = mockk<GlucoseStatusAIMI>(relaxed = true) {
                every { glucose } returns 100.0
                every { delta } returns 0.0
                every { shortAvgDelta } returns 0.0
                every { longAvgDelta } returns 0.0
                every { combinedDelta } returns 0.0
                every { date } returns now
            },
            currenttemp = mockk<CurrentTemp>(relaxed = true) {
                every { duration } returns 0
                every { rate } returns 1.0
            },
            iob_data_array = arrayOf(mockk<IobTotal>(relaxed = true) {
                every { iob } returns 0.5
                every { lastBolusTime } returns 0L
            }),
            profile = mockk<OapsProfileAimi>(relaxed = true) {
                every { TDD } returns 48.0
                every { current_basal } returns 1.0
                every { max_daily_basal } returns 48.0
                every { max_basal } returns 5.0
                every { max_iob } returns 20.0
                every { sens } returns 50.0
                every { target_bg } returns 100.0
                every { carb_ratio } returns 10.0
                every { dia } returns 5.0
                every { peakTime } returns 60.0
                every { enableUAM } returns false
                every { currentActivity } returns 0.0
                every { futureActivity } returns 0.0
                every { sensorLagActivity } returns 0.0
                every { historicActivity } returns 0.0
                every { lgsThreshold } returns 70
            },
            autosens_data = mockk<AutosensResult>(relaxed = true) {
                every { ratio } returns 1.0
            },
            mealData = mockk<MealData>(relaxed = true) {
                every { lastCarbTime } returns 1L
                every { mealCOB } returns 0.0
            },
            microBolusAllowed = true,
            currentTime = now,
            flatBGsDetected = false,
            dynIsfMode = false,
            uiInteraction = mockk<UiInteraction>(relaxed = true),
            extraDebug = "exercise lockout scenario"
        )
        assertThat(rt).isNotNull()
        assertThat(rt.reason.toString()).contains("Sport / contexte AIMI activité")
        verify(exactly = 1) {
            basalNeuralLearner.updateLearning(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }
}
