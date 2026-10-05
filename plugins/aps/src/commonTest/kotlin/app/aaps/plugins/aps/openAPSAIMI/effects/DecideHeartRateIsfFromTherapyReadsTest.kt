package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.HR
import app.aaps.core.data.model.ICfg
import app.aaps.core.data.model.SC
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.BooleanComposedNonPreferenceKey
import app.aaps.core.keys.interfaces.BooleanNonPreferenceKey
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.ComposedKey
import app.aaps.core.keys.interfaces.DoubleComposedNonPreferenceKey
import app.aaps.core.keys.interfaces.DoubleNonPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey
import app.aaps.core.keys.interfaces.IntComposedNonPreferenceKey
import app.aaps.core.keys.interfaces.IntNonPreferenceKey
import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.core.keys.interfaces.LongComposedNonPreferenceKey
import app.aaps.core.keys.interfaces.LongNonPreferenceKey
import app.aaps.core.keys.interfaces.LongPreferenceKey
import app.aaps.core.keys.interfaces.NonPreferenceKey
import app.aaps.core.keys.interfaces.PreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.StringComposedNonPreferenceKey
import app.aaps.core.keys.interfaces.StringNonPreferenceKey
import app.aaps.core.keys.interfaces.StringPreferenceKey
import app.aaps.core.keys.interfaces.UnitDoublePreferenceKey
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The Android scene `autosensHalfDoublesScheduledBasalAndRestingHeartRateStrengthensIsf` seeds four
 * watch samples and an empty step list. The same samples, read through the common contract with the
 * same windows as `persistenceLayer`, strengthen ISF 50 to 45 and lock
 * `HR_TREND_ISF x0.90 (hr10 110 / hr60 88, steps10 0)`.
 *
 * Android `DetermineBasalAIMI2` is not switched onto this contract.
 */
class DecideHeartRateIsfFromTherapyReadsTest {

    @Test
    fun windowsMatchTheAndroidQueries() {
        val now = 1_700_000_000_000L
        val inside = hr(now - 40 * 60_000L, 80.0)
        val onStart = hr(now - THERAPY_HEART_RATE_LOOKBACK_MS, 70.0)
        val tooOld = hr(now - THERAPY_HEART_RATE_LOOKBACK_MS - 1, 200.0)
        val stepsInside = steps(now - 10 * 60_000L, steps10 = 0)
        val stepsTooOld = steps(now - THERAPY_STEPS_LOOKBACK_MS - 1, steps10 = 99)
        val kept = bolus(id = 2L, timestamp = now - 60_000L)
        val olderId = bolus(id = 1L, timestamp = now - 120_000L)
        val invalid = bolus(id = 9L, timestamp = now, valid = false)
        val historic = bolus(id = 8L, timestamp = now, referenceId = 3L)
        val beforeBolusStart = bolus(id = 7L, timestamp = now - 5 * 60_000L)
        val store = MemoryAimiTherapyReads(
            heartRates = listOf(tooOld, inside, onStart),
            steps = listOf(stepsTooOld, stepsInside),
            boluses = listOf(olderId, kept, invalid, historic, beforeBolusStart),
        )
        val log = mutableListOf<String>()
        val caches = readTherapyCaches(
            reads = store,
            nowMs = now,
            bolusFromMs = now - 3 * 60_000L,
            bolusAscending = true,
            consoleLog = log,
        )
        assertEquals(listOf(onStart, inside), caches.heartRates)
        assertEquals(listOf(stepsInside), caches.steps)
        assertEquals(listOf(kept, olderId), caches.boluses)
        assertTrue(log.isEmpty())
    }

    @Test
    fun theSameFourSamplesStrengthenIsfTo45() {
        val now = aimiWallClockMs()
        val store = MemoryAimiTherapyReads(
            heartRates = listOf(
                hr(now - 40 * 60_000L, 80.0),
                hr(now - 30 * 60_000L, 80.0),
                hr(now - 20 * 60_000L, 80.0),
                hr(now - 2 * 60_000L, 110.0),
            ),
        )
        val log = mutableListOf<String>()
        val result = decideHeartRateIsfFromTherapyReads(
            reads = store,
            nowMs = now,
            stepsFromWatch = true,
            startingSensitivity = 50.0f,
            delta = 2.0f,
            glucoseMgdl = 140.0,
            bgMgdl = 160.0,
            iob = 1.0,
            bolusFromMs = 0L,
            bolusAscending = true,
            profile = profile(),
            preferences = StepsWatchPreferences(enabled = true),
            consoleLog = log,
        )
        assertEquals(45.0f, result.variableSensitivity, 0.001f)
        assertEquals(
            "💓 HR_TREND_ISF x0.90 (hr10 110 / hr60 88, steps10 0)",
            result.logLine,
        )
        assertTrue(log.contains("💓 HR_TREND_ISF x0.90 (hr10 110 / hr60 88, steps10 0)"))
        assertTrue(result.boluses.isEmpty())
        assertTrue(result.variableSensitivity <= 50.0f)
    }

    @Test
    fun aHeartRateReadFailureIsLoggedAndDoesNotStrengthen() {
        val store = object : AimiTherapyReads by MemoryAimiTherapyReads() {
            override fun getHeartRatesFromTimeToTime(startTime: Long, endTime: Long): List<HR> {
                throw IllegalStateException("db down")
            }
        }
        val log = mutableListOf<String>()
        val result = decideHeartRateIsfFromTherapyReads(
            reads = store,
            nowMs = aimiWallClockMs(),
            stepsFromWatch = true,
            startingSensitivity = 50.0f,
            delta = 2.0f,
            glucoseMgdl = 140.0,
            bgMgdl = 160.0,
            iob = 1.0,
            bolusFromMs = 0L,
            bolusAscending = true,
            profile = profile(),
            preferences = StepsWatchPreferences(enabled = true),
            consoleLog = log,
        )
        assertEquals(50.0f, result.variableSensitivity, 0.001f)
        assertEquals(null, result.logLine)
        assertEquals(
            listOf("HR windows failed (IllegalStateException): db down — averages 80, baseline not real"),
            log,
        )
    }

    @Test
    fun anErrorFromTheReadPropagates() {
        val store = object : AimiTherapyReads by MemoryAimiTherapyReads() {
            override fun getHeartRatesFromTimeToTime(startTime: Long, endTime: Long): List<HR> {
                throw OutOfMemoryError("disk")
            }
        }
        assertFailsWith<OutOfMemoryError> {
            decideHeartRateIsfFromTherapyReads(
                reads = store,
                nowMs = aimiWallClockMs(),
                stepsFromWatch = true,
                startingSensitivity = 50.0f,
                delta = 2.0f,
                glucoseMgdl = 140.0,
                bgMgdl = 160.0,
                iob = 1.0,
                bolusFromMs = 0L,
                bolusAscending = true,
                profile = profile(),
                preferences = StepsWatchPreferences(enabled = true),
                consoleLog = mutableListOf(),
            )
        }
    }

    private fun hr(timestamp: Long, bpm: Double) = HR(
        duration = 60_000L,
        timestamp = timestamp,
        beatsPerMinute = bpm,
        device = "watch",
    )

    private fun steps(timestamp: Long, steps10: Int) = SC(
        duration = 60_000L,
        timestamp = timestamp,
        steps5min = 0,
        steps10min = steps10,
        steps15min = 0,
        steps30min = 0,
        steps60min = 0,
        steps180min = 0,
        device = "watch",
    )

    private fun bolus(
        id: Long,
        timestamp: Long,
        valid: Boolean = true,
        referenceId: Long? = null,
    ) = BS(
        id = id,
        timestamp = timestamp,
        amount = 1.0,
        type = BS.Type.SMB,
        isValid = valid,
        referenceId = referenceId,
        iCfg = ICfg(insulinLabel = "test", peak = 75, dia = 5.0, concentration = 1.0),
    )

    private fun profile() = OapsProfileAimi(
        dia = 5.0,
        min_5m_carbimpact = 0.0,
        max_iob = 10.0,
        max_daily_basal = 1.0,
        max_basal = 1.0,
        min_bg = 80.0,
        max_bg = 120.0,
        target_bg = 100.0,
        carb_ratio = 10.0,
        sens = 50.0,
        autosens_adjust_targets = false,
        max_daily_safety_multiplier = 1.0,
        current_basal_safety_multiplier = 1.0,
        high_temptarget_raises_sensitivity = false,
        low_temptarget_lowers_sensitivity = false,
        sensitivity_raises_target = false,
        resistance_lowers_target = false,
        adv_target_adjustments = false,
        exercise_mode = false,
        half_basal_exercise_target = 160,
        maxCOB = 120,
        skip_neutral_temps = false,
        remainingCarbsCap = 0,
        enableUAM = false,
        A52_risk_enable = false,
        SMBInterval = 3,
        enableSMB_with_COB = false,
        enableSMB_with_temptarget = false,
        allowSMB_with_high_temptarget = false,
        enableSMB_always = false,
        enableSMB_after_carbs = false,
        maxSMBBasalMinutes = 30,
        maxUAMSMBBasalMinutes = 30,
        bolus_increment = 0.1,
        carbsReqThreshold = 1,
        current_basal = 1.0,
        temptargetSet = false,
        autosens_max = 1.5,
        out_units = "mg/dl",
        lgsThreshold = 70,
        variable_sens = 50.0,
        insulinDivisor = 1,
        TDD = 40.0,
        peakTime = 75.0,
        futureActivity = 0.0,
        sensorLagActivity = 0.0,
        historicActivity = 0.0,
        currentActivity = 0.0,
    )
}

internal class StepsWatchPreferences(private val enabled: Boolean) : Preferences {

    override val simpleMode: Boolean = false
    override val apsMode: Boolean = true
    override val nsclientMode: Boolean = false
    override val pumpControlMode: Boolean = false

    override fun get(key: BooleanNonPreferenceKey): Boolean = key.defaultValue
    override fun getIfExists(key: BooleanNonPreferenceKey): Boolean = key.defaultValue
    override fun put(key: BooleanNonPreferenceKey, value: Boolean) {}
    override fun observe(key: BooleanNonPreferenceKey): StateFlow<Boolean> = MutableStateFlow(key.defaultValue)
    override fun get(key: BooleanPreferenceKey): Boolean =
        if (key == BooleanKey.OApsAIMIEnableStepsFromWatch) enabled else key.defaultValue
    override fun get(key: BooleanComposedNonPreferenceKey, vararg arguments: Any): Boolean = key.defaultValue
    override fun get(key: BooleanComposedNonPreferenceKey, vararg arguments: Any, defaultValue: Boolean): Boolean = defaultValue
    override fun getIfExists(key: BooleanComposedNonPreferenceKey, vararg arguments: Any): Boolean = key.defaultValue
    override fun put(key: BooleanComposedNonPreferenceKey, vararg arguments: Any, value: Boolean) {}
    override fun observe(key: BooleanComposedNonPreferenceKey, vararg arguments: Any): StateFlow<Boolean> =
        MutableStateFlow(key.defaultValue)
    override fun remove(key: ComposedKey, vararg arguments: Any) {}
    override fun get(key: StringNonPreferenceKey): String = key.defaultValue
    override fun getIfExists(key: StringNonPreferenceKey): String = key.defaultValue
    override fun put(key: StringNonPreferenceKey, value: String) {}
    override fun observe(key: StringNonPreferenceKey): StateFlow<String> = MutableStateFlow(key.defaultValue)
    override fun get(key: StringPreferenceKey): String = key.defaultValue
    override fun get(key: StringComposedNonPreferenceKey, vararg arguments: Any): String = key.defaultValue
    override fun getIfExists(key: StringComposedNonPreferenceKey, vararg arguments: Any): String = key.defaultValue
    override fun put(key: StringComposedNonPreferenceKey, vararg arguments: Any, value: String) {}
    override fun observe(key: StringComposedNonPreferenceKey, vararg arguments: Any): StateFlow<String> =
        MutableStateFlow(key.defaultValue)
    override fun get(key: DoubleNonPreferenceKey): Double = key.defaultValue
    override fun get(key: DoublePreferenceKey): Double = key.defaultValue
    override fun getIfExists(key: DoublePreferenceKey): Double = key.defaultValue
    override fun put(key: DoubleNonPreferenceKey, value: Double) {}
    override fun observe(key: DoubleNonPreferenceKey): StateFlow<Double> = MutableStateFlow(key.defaultValue)
    override fun get(key: DoubleComposedNonPreferenceKey, vararg arguments: Any): Double = key.defaultValue
    override fun getIfExists(key: DoubleComposedNonPreferenceKey, vararg arguments: Any): Double = key.defaultValue
    override fun put(key: DoubleComposedNonPreferenceKey, vararg arguments: Any, value: Double) {}
    override fun observe(key: DoubleComposedNonPreferenceKey, vararg arguments: Any): StateFlow<Double> =
        MutableStateFlow(key.defaultValue)
    override fun get(key: UnitDoublePreferenceKey): Double = key.defaultValue
    override fun getIfExists(key: UnitDoublePreferenceKey): Double = key.defaultValue
    override fun put(key: UnitDoublePreferenceKey, value: Double) {}
    override fun observe(key: UnitDoublePreferenceKey): StateFlow<Double> = MutableStateFlow(key.defaultValue)
    override fun get(key: IntNonPreferenceKey): Int = key.defaultValue
    override fun getIfExists(key: IntNonPreferenceKey): Int = key.defaultValue
    override fun put(key: IntComposedNonPreferenceKey, vararg arguments: Any, value: Int) {}
    override fun put(key: IntNonPreferenceKey, value: Int) {}
    override fun observe(key: IntNonPreferenceKey): StateFlow<Int> = MutableStateFlow(key.defaultValue)
    override fun inc(key: IntNonPreferenceKey) {}
    override fun get(key: IntComposedNonPreferenceKey, vararg arguments: Any): Int = key.defaultValue
    override fun observe(key: IntComposedNonPreferenceKey, vararg arguments: Any): StateFlow<Int> =
        MutableStateFlow(key.defaultValue)
    override fun get(key: IntPreferenceKey): Int = key.defaultValue
    override fun get(key: LongNonPreferenceKey): Long = key.defaultValue
    override fun getIfExists(key: LongNonPreferenceKey): Long = key.defaultValue
    override fun put(key: LongNonPreferenceKey, value: Long) {}
    override fun observe(key: LongNonPreferenceKey): StateFlow<Long> = MutableStateFlow(key.defaultValue)
    override fun get(key: LongPreferenceKey): Long = key.defaultValue
    override fun inc(key: LongNonPreferenceKey) {}
    override fun get(key: LongComposedNonPreferenceKey, vararg arguments: Any): Long = key.defaultValue
    override fun getIfExists(key: LongComposedNonPreferenceKey, vararg arguments: Any): Long = key.defaultValue
    override fun put(key: LongComposedNonPreferenceKey, vararg arguments: Any, value: Long) {}
    override fun observe(key: LongComposedNonPreferenceKey, vararg arguments: Any): StateFlow<Long> =
        MutableStateFlow(key.defaultValue)
    override fun remove(key: NonPreferenceKey) {}
    override fun isUnitDependent(key: String): Boolean = false
    override fun get(key: String): NonPreferenceKey? = null
    override fun getIfExists(key: String): NonPreferenceKey? = null
    override fun registerPreferences(keys: List<NonPreferenceKey>) {}
    override fun allMatchingStrings(key: ComposedKey): List<String> = emptyList()
    override fun allMatchingInts(key: ComposedKey): List<Int> = emptyList()
    override fun isExportableKey(key: String): Boolean = false
    override fun getAllPreferenceKeys(): List<PreferenceKey> = emptyList()
}
