package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.data.model.HR
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A HealthKit read session, behind [HealthKitWindowPort], asks for the same windows Android
 * reads from the watch and writes [app.aaps.plugins.aps.openAPSAIMI.physio.HealthContextSnapshot].
 *
 * The mock stands in for HealthKit. The four samples of
 * `autosensHalfDoublesScheduledBasalAndRestingHeartRateStrengthensIsf` (80, 80, 80, then 110 bpm)
 * strengthen ISF 50 to 45 and lock `HR_TREND_ISF x0.90 (hr10 110 / hr60 88, steps10 0)`.
 *
 * Authorization is requested only when `IosClientConfig.APS` and the engine switch are both on.
 */
class HealthKitReadSessionTest {

    @Test
    fun mockedHealthKitReplaysTheIsf45Line() {
        val now = aimiWallClockMs()
        val samples = listOf(
            hr(now - 40 * 60_000L, 80.0),
            hr(now - 30 * 60_000L, 80.0),
            hr(now - 20 * 60_000L, 80.0),
            hr(now - 2 * 60_000L, 110.0),
        )
        val asked = mutableListOf<String>()
        val port = object : HealthKitWindowPort {
            override fun requestReadAuthorization() {
                asked += "auth"
            }

            override fun stepsSum(nowMs: Long, minutes: Int): Int {
                assertEquals(now, nowMs)
                asked += "steps$minutes"
                return 0
            }

            override fun heartRateSamples(nowMs: Long, minutes: Int): List<HR> {
                assertEquals(now, nowMs)
                asked += "hr$minutes"
                val start = nowMs - minutes * 60_000L
                return samples.filter { it.timestamp + it.duration >= start }
            }

            override fun restingHeartRateBpm(nowMs: Long, hours: Int): Int {
                assertEquals(now, nowMs)
                asked += "rhr$hours"
                return 60
            }
        }
        val log = mutableListOf<String>()
        val session = openHealthKitReadSession(
            port = port,
            nowMs = now,
            apsEnabled = false,
            engineEnabled = false,
            log = log,
        )
        assertEquals(listOf("steps5", "steps15", "steps60", "hr60", "rhr24"), asked)
        assertFalse(session.authorizationRequested)
        assertEquals(110, session.windows.hrAvg10)
        assertEquals(88, session.windows.hrAvg60)
        assertEquals(110, session.windows.snapshot.hrNow)
        assertEquals(110, session.windows.snapshot.hrAvg15m)
        assertEquals(60, session.windows.snapshot.rhrResting)
        assertEquals(0, session.windows.snapshot.stepsLast5m)
        assertEquals(0, session.windows.snapshot.stepsLast15m)
        assertEquals(0, session.windows.snapshot.stepsLast60m)
        assertEquals("HealthKit", session.windows.snapshot.source)
        assertFalse(session.windows.snapshot.isValid)
        assertTrue(log.isEmpty())

        val isfLog = mutableListOf<String>()
        val result = decideHeartRateIsfFromTherapyReads(
            reads = MemoryAimiTherapyReads(heartRates = session.heartRates),
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
            consoleLog = isfLog,
        )
        assertEquals(45.0f, result.variableSensitivity, 0.001f)
        assertEquals(
            "💓 HR_TREND_ISF x0.90 (hr10 110 / hr60 88, steps10 0)",
            result.logLine,
        )
        assertTrue(isfLog.contains("💓 HR_TREND_ISF x0.90 (hr10 110 / hr60 88, steps10 0)"))
    }

    @Test
    fun authorizationIsRequestedOnlyWhenBothSwitchesAreOn() {
        val events = mutableListOf<String>()
        val port = recordingPort(events)
        val now = 1_700_000_000_000L
        openHealthKitReadSession(port, now, apsEnabled = false, engineEnabled = false, log = mutableListOf())
        openHealthKitReadSession(port, now, apsEnabled = true, engineEnabled = false, log = mutableListOf())
        openHealthKitReadSession(port, now, apsEnabled = false, engineEnabled = true, log = mutableListOf())
        assertFalse(events.contains("auth"))
        val before = events.size
        openHealthKitReadSession(port, now, apsEnabled = true, engineEnabled = true, log = mutableListOf())
        assertEquals("auth", events[before])
        assertEquals(listOf("steps5", "steps15", "steps60", "hr60", "rhr24"), events.drop(before + 1))
        assertEquals(1, events.count { it == "auth" })
    }

    @Test
    fun aPortFailureLogsTheWearableLineAndDoesNotStrengthen() {
        val port = object : HealthKitWindowPort {
            override fun requestReadAuthorization() = Unit
            override fun stepsSum(nowMs: Long, minutes: Int): Int = throw IllegalStateException("healthkit down")
            override fun heartRateSamples(nowMs: Long, minutes: Int): List<HR> = emptyList()
            override fun restingHeartRateBpm(nowMs: Long, hours: Int): Int = 0
        }
        val log = mutableListOf<String>()
        val session = openHealthKitReadSession(
            port = port,
            nowMs = aimiWallClockMs(),
            apsEnabled = false,
            engineEnabled = false,
            log = log,
        )
        assertEquals(0, session.windows.snapshot.hrNow)
        assertEquals(0, session.heartRates.size)
        assertFalse(session.authorizationRequested)
        assertEquals(
            listOf("WEARABLE snapshot failed (IllegalStateException): healthkit down — snapshot empty"),
            log,
        )
        val isfLog = mutableListOf<String>()
        val result = decideHeartRateIsfFromTherapyReads(
            reads = MemoryAimiTherapyReads(heartRates = session.heartRates),
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
            consoleLog = isfLog,
        )
        assertEquals(50.0f, result.variableSensitivity, 0.001f)
        assertEquals(null, result.logLine)
    }

    private fun recordingPort(events: MutableList<String>) = object : HealthKitWindowPort {
        override fun requestReadAuthorization() {
            events += "auth"
        }

        override fun stepsSum(nowMs: Long, minutes: Int): Int {
            events += "steps$minutes"
            return 0
        }

        override fun heartRateSamples(nowMs: Long, minutes: Int): List<HR> {
            events += "hr$minutes"
            return emptyList()
        }

        override fun restingHeartRateBpm(nowMs: Long, hours: Int): Int {
            events += "rhr$hours"
            return 60
        }
    }

    private fun hr(timestamp: Long, bpm: Double) = HR(
        duration = 60_000L,
        timestamp = timestamp,
        beatsPerMinute = bpm,
        device = "HealthKit",
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
