package app.aaps.plugins.aps.openAPSAIMI.advisor

import androidx.collection.LongSparseArray
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.stats.TIR
import app.aaps.core.interfaces.stats.TirCalculator
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.StringPreferenceKey
import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.AimiTuningContext
import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.TuningContextEngine
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The advisor used to start from hard-coded numbers (TIR 65 %, mean 160 mg/dL, TDD 45 U) and only
 * overwrite the ones a calculator could give. A caller could not tell a real patient from that
 * invented one. These tests pin the new rule: an unread number stays `null` and
 * [AdvisorMetrics.dataSufficiency] says the block must not drive any settings change.
 */
class AimiAdvisorMetricsSufficiencyTest {

    private val preferences = mockk<Preferences>(relaxed = true)
    private val persistenceLayer = mockk<PersistenceLayer>(relaxed = true)
    private val profileFunction = mockk<ProfileFunction>(relaxed = true)
    private val rh = mockk<ResourceHelper>(relaxed = true)

    @BeforeEach
    fun setUp() {
        every { preferences.get(any<BooleanPreferenceKey>()) } answers { firstArg<BooleanPreferenceKey>().defaultValue }
        every { preferences.get(any<DoublePreferenceKey>()) } answers { firstArg<DoublePreferenceKey>().defaultValue }
        every { preferences.get(any<StringPreferenceKey>()) } answers { firstArg<StringPreferenceKey>().defaultValue }
        coEvery { profileFunction.getProfile() } returns null
        coEvery { persistenceLayer.getBgReadingsDataFromTimeToTime(any(), any(), any()) } returns emptyList()
    }

    private fun service(tirCalculator: TirCalculator?) = AimiAdvisorService(
        profileFunction = profileFunction,
        persistenceLayer = persistenceLayer,
        preferences = preferences,
        rh = rh,
        tirCalculator = tirCalculator,
    )

    /** One day of TIR, with the percentages the calculator would report. */
    private fun tirDays(inRangePct: Double?, belowPct: Double?, abovePct: Double?): LongSparseArray<TIR> {
        val tir = mockk<TIR>(relaxed = true)
        every { tir.inRangePct() } returns inRangePct
        every { tir.belowPct() } returns belowPct
        every { tir.abovePct() } returns abovePct
        val days = LongSparseArray<TIR>()
        days.put(1L, tir)
        return days
    }

    private fun workingCalculator(): TirCalculator {
        val calculator = mockk<TirCalculator>(relaxed = true)
        val days = tirDays(inRangePct = 72.0, belowPct = 3.0, abovePct = 25.0)
        coEvery { calculator.calculate(any(), any(), any()) } returns days
        every { calculator.averageTIR(any()) } returns days.valueAt(0)
        every { calculator.calculateDaily(any(), any()) } returns LongSparseArray()
        return calculator
    }

    private fun glucoseReading(value: Double) = GV(
        timestamp = 1L,
        raw = null,
        value = value,
        trendArrow = TrendArrow.NONE,
        noise = null,
        sourceSensor = SourceSensor.UNKNOWN,
    )

    @Test
    fun `no calculator gives unknown metrics, not an average patient`() {
        val metrics = service(tirCalculator = null).collectContext(7).metrics

        assertThat(metrics.dataSufficiency).isEqualTo(AdvisorDataSufficiency.INSUFFICIENT)
        assertThat(metrics.isInsufficient).isTrue()
        assertThat(metrics.tir70_180).isNull()
        assertThat(metrics.timeBelow70).isNull()
        assertThat(metrics.timeAbove180).isNull()
        assertThat(metrics.meanBg).isNull()
        assertThat(metrics.gmi).isNull()
        assertThat(metrics.tdd).isNull()
        assertThat(metrics.daysCovered).isEqualTo(0)
    }

    @Test
    fun `a failing query gives unknown metrics`() {
        val calculator = mockk<TirCalculator>(relaxed = true)
        coEvery { calculator.calculate(any(), any(), any()) } throws IllegalStateException("database is gone")

        val metrics = service(calculator).collectContext(7).metrics

        assertThat(metrics.dataSufficiency).isEqualTo(AdvisorDataSufficiency.INSUFFICIENT)
        assertThat(metrics.tir70_180).isNull()
        assertThat(metrics.timeBelow70).isNull()
        assertThat(metrics.timeAbove180).isNull()
    }

    @Test
    fun `a history too short gives unknown metrics instead of zero percent lows`() {
        // With no reading in the window the calculator reports null percentages, not 0.
        val calculator = mockk<TirCalculator>(relaxed = true)
        val days = tirDays(inRangePct = null, belowPct = null, abovePct = null)
        coEvery { calculator.calculate(any(), any(), any()) } returns days
        every { calculator.averageTIR(any()) } returns days.valueAt(0)
        every { calculator.calculateDaily(any(), any()) } returns LongSparseArray()

        val metrics = service(calculator).collectContext(7).metrics

        assertThat(metrics.dataSufficiency).isEqualTo(AdvisorDataSufficiency.INSUFFICIENT)
        assertThat(metrics.timeBelow70).isNull()
        assertThat(metrics.tir70_180).isNull()
        assertThat(metrics.bgReadingCount).isEqualTo(0)
    }

    @Test
    fun `enough history keeps the measured values`() {
        coEvery { persistenceLayer.getBgReadingsDataFromTimeToTime(any(), any(), any()) } returns
            listOf(glucoseReading(100.0), glucoseReading(140.0), glucoseReading(120.0))

        val metrics = service(workingCalculator()).collectContext(7).metrics

        assertThat(metrics.dataSufficiency).isEqualTo(AdvisorDataSufficiency.GOOD)
        assertThat(metrics.isInsufficient).isFalse()
        assertThat(metrics.tir70_180).isEqualTo(0.72)
        assertThat(metrics.timeBelow70).isEqualTo(0.03)
        assertThat(metrics.timeAbove180).isEqualTo(0.25)
        assertThat(metrics.meanBg).isEqualTo(120.0)
        assertThat(metrics.bgReadingCount).isEqualTo(3)
        assertThat(metrics.daysCovered).isEqualTo(1)
    }

    @Test
    fun `a glucose reading below the noise floor is not counted`() {
        coEvery { persistenceLayer.getBgReadingsDataFromTimeToTime(any(), any(), any()) } returns
            listOf(glucoseReading(10.0))

        val metrics = service(workingCalculator()).collectContext(7).metrics

        assertThat(metrics.meanBg).isNull()
        assertThat(metrics.variabilityCv).isNull()
        assertThat(metrics.bgReadingCount).isEqualTo(0)
    }

    @Test
    fun `unknown metrics propose no PKPD change`() {
        val unknown = service(tirCalculator = null).collectContext(7)

        val suggestions = PkpdAdvisor().analysePkpd(unknown.metrics, unknown.pkpdPrefs, rh, null)

        assertThat(suggestions).isEmpty()
    }

    @Test
    fun `unknown metrics block the tuning plan`() {
        val unknown = service(tirCalculator = null).collectContext(7)

        val plan = TuningContextEngine.computePlan(
            requestedContext = AimiTuningContext.HYPER_STABLE,
            metrics = unknown.metrics,
            preferences = preferences,
            t3cBrittleMode = false,
        )

        assertThat(plan.changes).isEmpty()
        assertThat(plan.blockedReason).isEqualTo(TuningContextEngine.NO_DATA_BLOCK)
    }
}
