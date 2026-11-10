package app.aaps.plugins.aps.openAPSAIMI

import android.os.Environment
import androidx.collection.LongSparseArray
import app.aaps.core.data.model.TDD
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
import java.util.concurrent.atomic.AtomicInteger
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdLearnedState

/**
 * Regression: per-invocation caches for TDD 1d and TIR (1d, 65–180) must not duplicate suspend/DB work.
 * Uses the same loose harness as [benchmarks.EngineBenchmarks].
 */
class DetermineBasalAimiPerInvocationCacheTest {

    private lateinit var engine: DetermineBasalaimiSMB2
    private val persistenceLayer = mockk<PersistenceLayer>(relaxed = true)
    private val tddCalculate1dCalls = AtomicInteger(0)
    private val tddCalculateDaily24hCalls = AtomicInteger(0)
    private val tirCalculate65180Calls = AtomicInteger(0)
    private lateinit var basalNeuralLearner: BasalNeuralLearner

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
        coEvery { persistenceLayer.getTherapyEventDataFromTime(any(), any()) } returns emptyList()
        coEvery { persistenceLayer.getTherapyEventDataFromTime(any(), any(), any()) } returns emptyList()
        coEvery { persistenceLayer.getTherapyEventDataFromToTime(any(), any()) } returns emptyList()
        coEvery { persistenceLayer.getBgReadingsDataFromTime(any(), any()) } returns emptyList()

        val tddCalculator = mockk<TddCalculator>(relaxed = true)
        coEvery { tddCalculator.calculate(1L, false) } coAnswers {
            tddCalculate1dCalls.incrementAndGet()
            LongSparseArray<TDD>().apply {
                put(1L, TDD(timestamp = System.currentTimeMillis(), totalAmount = 48.0))
            }
        }
        coEvery { tddCalculator.calculateDaily(-24, 0) } coAnswers {
            tddCalculateDaily24hCalls.incrementAndGet()
            TDD(timestamp = System.currentTimeMillis(), totalAmount = 48.0)
        }

        val tirCalculator = mockk<TirCalculator>(relaxed = true)
        coEvery { tirCalculator.calculate(1L, 65.0, 180.0) } coAnswers {
            tirCalculate65180Calls.incrementAndGet()
            LongSparseArray()
        }

        basalNeuralLearner = mockk(relaxed = true)

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
            this.persistenceLayer = this@DetermineBasalAimiPerInvocationCacheTest.persistenceLayer
            this.tddCalculator = tddCalculator
            this.tirCalculator = tirCalculator
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
            basalNeuralLearner = this@DetermineBasalAimiPerInvocationCacheTest.basalNeuralLearner
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
    fun `single determine_basal uses tdd calculate 1d and tir 65180 at most once each`() {
        val now = System.currentTimeMillis()
        engine.determine_basal(
            glucose_status = glucoseStatusFresh(now),
            currenttemp = mockk<CurrentTemp>(relaxed = true),
            iob_data_array = arrayOf(mockk<IobTotal>(relaxed = true)),
            profile = profileMinimal(),
            autosens_data = mockk<AutosensResult>(relaxed = true),
            mealData = mockk<MealData>(relaxed = true),
            microBolusAllowed = true,
            currentTime = now,
            flatBGsDetected = false,
            dynIsfMode = false,
            uiInteraction = mockk<UiInteraction>(relaxed = true),
            extraDebug = "cache test"
        )

        assertThat(tddCalculate1dCalls.get()).isEqualTo(1)
        assertThat(tddCalculateDaily24hCalls.get()).isEqualTo(1)
        assertThat(tirCalculate65180Calls.get()).isEqualTo(1)
        // Décision A : pas d'updateLearning basal neural sur le chemin nominal (hors logDecisionFinal).
        verify(exactly = 0) {
            basalNeuralLearner.updateLearning(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `second determine_basal invocation refetches tdd 1d and tir 65180`() {
        val now = System.currentTimeMillis()
        repeat(2) {
            engine.determine_basal(
                glucose_status = glucoseStatusFresh(now),
                currenttemp = mockk<CurrentTemp>(relaxed = true),
                iob_data_array = arrayOf(mockk<IobTotal>(relaxed = true)),
                profile = profileMinimal(),
                autosens_data = mockk<AutosensResult>(relaxed = true),
                mealData = mockk<MealData>(relaxed = true),
                microBolusAllowed = true,
                currentTime = now,
                flatBGsDetected = false,
                dynIsfMode = false,
                uiInteraction = mockk<UiInteraction>(relaxed = true),
                extraDebug = "cache test 2nd invocation"
            )
        }
        assertThat(tddCalculate1dCalls.get()).isEqualTo(2)
        assertThat(tddCalculateDaily24hCalls.get()).isEqualTo(2)
        assertThat(tirCalculate65180Calls.get()).isEqualTo(2)
    }

    @Test
    fun `stale glucose exits before tdd calculate 1d block tir warmup still runs once`() {
        val now = System.currentTimeMillis()
        val staleReading = now - 20L * 60L * 1000L
        engine.determine_basal(
            glucose_status = mockk<GlucoseStatusAIMI>(relaxed = true) {
                every { glucose } returns 120.0
                every { delta } returns 0.0
                every { shortAvgDelta } returns 0.0
                every { longAvgDelta } returns 0.0
                every { combinedDelta } returns 0.0
                every { date } returns staleReading
            },
            currenttemp = mockk<CurrentTemp>(relaxed = true),
            iob_data_array = arrayOf(mockk<IobTotal>(relaxed = true)),
            profile = profileMinimal(),
            autosens_data = mockk<AutosensResult>(relaxed = true),
            mealData = mockk<MealData>(relaxed = true),
            microBolusAllowed = true,
            currentTime = now,
            flatBGsDetected = false,
            dynIsfMode = false,
            uiInteraction = mockk<UiInteraction>(relaxed = true),
            extraDebug = "cache test stale"
        )
        assertThat(tddCalculate1dCalls.get()).isEqualTo(0)
        assertThat(tddCalculateDaily24hCalls.get()).isEqualTo(1)
        assertThat(tirCalculate65180Calls.get()).isEqualTo(1)
        // Sortie notable : logDecisionFinal(STALE_DATA) → une passe learning (décision A, côté « exit »).
        verify(exactly = 1) {
            basalNeuralLearner.updateLearning(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    private fun glucoseStatusFresh(now: Long) = mockk<GlucoseStatusAIMI>(relaxed = true) {
        every { glucose } returns 120.0
        every { delta } returns 0.0
        every { shortAvgDelta } returns 0.0
        every { longAvgDelta } returns 0.0
        every { combinedDelta } returns 0.0
        every { date } returns now
    }

    private fun profileMinimal() = mockk<OapsProfileAimi>(relaxed = true) {
        every { TDD } returns 48.0
        every { current_basal } returns 1.0
        every { max_daily_basal } returns 48.0
        every { max_basal } returns 5.0
        every { max_iob } returns 20.0
        every { sens } returns 50.0
        every { target_bg } returns 100.0
        every { carb_ratio } returns 10.0
    }
}
