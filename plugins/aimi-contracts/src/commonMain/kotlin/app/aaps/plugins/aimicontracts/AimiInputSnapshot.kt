package app.aaps.plugins.aimicontracts

import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.OapsProfileAimi
import kotlinx.serialization.Serializable

/**
 * Immutable AIMI calculation input for one tick.
 *
 * The shell fills this before `evaluate`. The engine must not read Room, HealthKit, prefs or
 * the pump from here. What is not in this object is not an input of tick N.
 *
 * This W5 shape is the envelope and the fields we already know are required. It is not the full
 * annex-8 v1 DTO. Deferred on purpose (do not invent them here):
 * - 109 typed config keys (stay in the Android/iOS shell until listed)
 * - replay envelope (`engine-replay-v1`)
 * - glucose features such as duraISF / parabola
 * - TDD windows, TIR quality, COB future, site age
 * - full physio windows (steps 5–180 min, sleep, SpO2)
 * - Auditor / TPO / LLM (N+1 advice, never mutate tick N)
 */
@Serializable
data class AimiInputSnapshot(
    val meta: AimiTickMeta,
    val glucose: AimiGlucoseSnapshot,
    val pump: AimiPumpSnapshot,
    val profile: AimiProfileSnapshot,
    val insulin: AimiInsulinSnapshot,
    val meal: AimiMealSnapshot,
    val physiology: AimiPhysiologySnapshot,
    val config: AimiConfigSnapshot,
    val capabilities: AimiCapabilitySnapshot,
    val advanced: AimiAdvancedFeatures? = null,
)

/**
 * Optional advanced features for the tick.
 *
 * All fields are nullable: a missing feature is null, never 0. The engine
 * decides which features it consumes; unknown features are ignored.
 */
@Serializable
data class AimiAdvancedFeatures(
    val tddMgdl: TimedValue<Double>? = null,
    val tirPercent: TimedValue<Double>? = null,
    val duraIsfMgdlPerU: TimedValue<Double>? = null,
)

/** Why this tick ran. The iOS loop is CGM-BLE driven; a 5 minute timer is not the heartbeat. */
@Serializable
enum class AimiTickTrigger {
    Cgm,
    Pump,
    Manual,
    Recovery,
}

@Serializable
data class AimiTickMeta(
    val schemaVersion: Int,
    val tickId: Long,
    val wallClockEpochMs: Long,
    val monotonicMs: Long,
    val timezoneOffsetMinutes: Int,
    val trigger: AimiTickTrigger,
)

/**
 * Loop glucose for this tick.
 *
 * Sample time and age live on [TimedValue.Fresh] / [TimedValue.Stale], not on a second field.
 *
 * [sourceId] is the `SourceSensor.text` string (for example `AAPS-DexcomOnePlus` or
 * `AAPS-Libre3`). This module does not depend on `:core:data`.
 *
 * Staging / pre-soak glucose must arrive with [loopEligible] = false until the shell promotes it.
 */
@Serializable
data class AimiGlucoseSnapshot(
    val glucoseMgdl: TimedValue<Double>,
    val sourceId: String?,
    val warmup: AimiGlucoseWarmup,
    val loopEligible: Boolean,
    val delta: TimedValue<Double> = TimedValue.Missing("not captured"),
    val shortAvgDelta: TimedValue<Double> = TimedValue.Missing("not captured"),
    val longAvgDelta: TimedValue<Double> = TimedValue.Missing("not captured"),
    val noise: TimedValue<Double> = TimedValue.Missing("not captured"),
)

/** Coarse warm-up for the snapshot. Driver phases stay in the CGM plugin, not here. */
@Serializable
enum class AimiGlucoseWarmup {
    None,
    InProgress,
    Failed,
}

@Serializable
data class AimiPumpSnapshot(
    val profileBasalUPerHour: TimedValue<Double>,
    val tempBasalUPerHour: TimedValue<Double>,
    val tempBasalRemainingMs: Long?,
    val maxBolusU: Double?,
    val maxBasalUPerHour: Double?,
    val pumpCanSmb: Boolean,
    val pumpCanTempBasal: Boolean,
)

/**
 * Full profile for the tick.
 *
 * Carries the complete [OapsProfileAimi] (all fields) instead of a 6-field
 * subset. The shell fills this from the active profile before `evaluate`.
 */
@Serializable
data class AimiProfileSnapshot(
    val profile: OapsProfileAimi,
)

@Serializable
data class AimiInsulinSnapshot(
    val iobU: TimedValue<Double>,
    val activityUPerHour: TimedValue<Double>,
    val iobHistory: List<IobTotal> = emptyList(),
)

@Serializable
data class AimiMealSnapshot(
    val cobG: TimedValue<Double>,
    val lastCarbsG: TimedValue<Double>,
)

/**
 * Physio inputs. RMSSD and SDNN stay two fields. They do not share a baseline or a threshold.
 */
@Serializable
data class AimiPhysiologySnapshot(
    val heartRateBpm: TimedValue<Double>,
    val steps: TimedValue<Int>,
    val hrvRmssdMs: TimedValue<Double>,
    val hrvSdnnMs: TimedValue<Double>,
)

/**
 * Config frozen for this tick. Domain keys are not listed yet; the shell still owns prefs.
 */
@Serializable
data class AimiConfigSnapshot(
    val schemaVersion: Int,
)

/**
 * System permissions for this tick, not pump hardware.
 *
 * Whether the pump can physically take an SMB is [AimiPumpSnapshot.pumpCanSmb].
 * Constraint-plugin SMB rules stay deferred until config is typed.
 */
@Serializable
data class AimiCapabilitySnapshot(
    val closedLoopAllowed: Boolean,
)
