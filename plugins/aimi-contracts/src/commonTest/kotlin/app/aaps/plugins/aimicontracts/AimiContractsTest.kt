package app.aaps.plugins.aimicontracts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AimiContractsTest {

    @Test
    fun hello_is_a_fixed_string_with_no_aimi_logic() {
        assertEquals("aimi-contracts", AimiContracts.hello())
    }

    @Test
    fun missing_timed_value_is_not_zero() {
        val missing: TimedValue<Double> = TimedValue.Missing("not collected")
        assertNull(missing.valueOrNull)
        assertTrue(missing is TimedValue.Missing)
    }

    @Test
    fun denied_and_unsupported_are_not_missing_and_not_zero() {
        val denied: TimedValue<Double> = TimedValue.Denied("healthkit.heart_rate")
        val unsupported: TimedValue<Double> = TimedValue.Unsupported("healthkit.hrv")
        assertNull(denied.valueOrNull)
        assertNull(unsupported.valueOrNull)
        assertTrue(denied is TimedValue.Denied)
        assertTrue(unsupported is TimedValue.Unsupported)
    }

    @Test
    fun member_target_and_schedule_target_are_separate_fields() {
        val profile = AimiProfileSnapshot(
            memberTargetBgMgdl = 100.0,
            scheduleTargetBgMgdl = 110.0,
            isfMgdlPerU = TimedValue.Missing("not captured"),
            icGPerU = TimedValue.Missing("not captured"),
            diaMs = null,
            peakMs = null,
        )
        assertNotEquals(profile.memberTargetBgMgdl, profile.scheduleTargetBgMgdl)
    }

    @Test
    fun rmssd_and_sdnn_are_separate_fields() {
        val physio = AimiPhysiologySnapshot(
            heartRateBpm = TimedValue.Missing("not collected"),
            steps = TimedValue.Missing("not collected"),
            hrvRmssdMs = TimedValue.Fresh(value = 42.0, capturedAtEpochMs = 1L, ageMs = 0L),
            hrvSdnnMs = TimedValue.Fresh(value = 80.0, capturedAtEpochMs = 1L, ageMs = 0L),
        )
        assertEquals(42.0, physio.hrvRmssdMs.valueOrNull)
        assertEquals(80.0, physio.hrvSdnnMs.valueOrNull)
        assertNotEquals(physio.hrvRmssdMs.valueOrNull, physio.hrvSdnnMs.valueOrNull)
    }

    @Test
    fun snapshot_round_trip_copy_is_equal() {
        val missingBg: TimedValue<Double> = TimedValue.Missing("not collected")
        val snap = AimiInputSnapshot(
            meta = AimiTickMeta(
                schemaVersion = 1,
                tickId = 1L,
                wallClockEpochMs = 1L,
                monotonicMs = 1L,
                timezoneOffsetMinutes = 0,
                trigger = AimiTickTrigger.Cgm,
            ),
            glucose = AimiGlucoseSnapshot(
                glucoseMgdl = missingBg,
                sourceId = "AAPS-Libre3",
                warmup = AimiGlucoseWarmup.None,
                loopEligible = false,
            ),
            pump = AimiPumpSnapshot(
                profileBasalUPerHour = TimedValue.Missing("not collected"),
                tempBasalUPerHour = TimedValue.Missing("not collected"),
                tempBasalRemainingMs = null,
                maxBolusU = null,
                maxBasalUPerHour = null,
                pumpCanSmb = false,
                pumpCanTempBasal = false,
            ),
            profile = AimiProfileSnapshot(
                memberTargetBgMgdl = 100.0,
                scheduleTargetBgMgdl = 110.0,
                isfMgdlPerU = TimedValue.Missing("not captured"),
                icGPerU = TimedValue.Missing("not captured"),
                diaMs = null,
                peakMs = null,
            ),
            insulin = AimiInsulinSnapshot(
                iobU = TimedValue.Missing("not collected"),
                activityUPerHour = TimedValue.Missing("not collected"),
            ),
            meal = AimiMealSnapshot(
                cobG = TimedValue.Missing("not collected"),
                lastCarbsG = TimedValue.Missing("not collected"),
            ),
            physiology = AimiPhysiologySnapshot(
                heartRateBpm = TimedValue.Missing("not collected"),
                steps = TimedValue.Missing("not collected"),
                hrvRmssdMs = TimedValue.Missing("not collected"),
                hrvSdnnMs = TimedValue.Missing("not collected"),
            ),
            config = AimiConfigSnapshot(schemaVersion = 1),
            capabilities = AimiCapabilitySnapshot(closedLoopAllowed = false),
        )
        assertEquals(snap, snap.copy())
    }

    @Test
    fun hold_command_carries_stable_reason() {
        val hold = AimiTherapyCommand.Hold("ENGINE_NOT_EXTRACTED")
        assertEquals("ENGINE_NOT_EXTRACTED", hold.reasonCode)
    }
}
