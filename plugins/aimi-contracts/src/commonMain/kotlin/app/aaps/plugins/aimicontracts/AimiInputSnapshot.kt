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
    val autosens: AimiAutosensSnapshot = AimiAutosensSnapshot(),
    val bgQuality: AimiBgQualitySnapshot = AimiBgQualitySnapshot(),
    val kinetics: AimiKineticsSnapshot = AimiKineticsSnapshot(),
    /**
     * Dynamic ISF mode for this tick.
     *
     * Not in the 102 typed config keys (verified against
     * `_docs/kmp/generated/m1-config-keys.csv`); carried here as a tick input.
     * Default false = dynamic ISF off.
     */
    val dynIsfMode: Boolean = false,
)

/**
 * Autosens state for this tick.
 *
 * [ratio] is the sensitivity ratio computed by the autosens algorithm
 * (1.0 = neutral). [TimedValue.Missing] means autosens did not run;
 * the adapter falls back to 1.0 only in that case, and documents it.
 */
@Serializable
data class AimiAutosensSnapshot(
    val ratio: TimedValue<Double> = TimedValue.Missing("not captured"),
)

/**
 * CGM data quality for this tick.
 *
 * [flatBGsDetected] is a safety signal: when true, the engine must not
 * trust flat high readings (stuck sensor can mask a real low or fake a high).
 * Default false = no flat-data suspicion.
 */
@Serializable
data class AimiBgQualitySnapshot(
    val flatBGsDetected: Boolean = false,
    val noiseLevel: TimedValue<Double>? = null,
)

/**
 * Learned insulin kinetics for this tick.
 *
 * [effectiveDiaHours] and [effectivePeakMinutes] come from the kinetics
 * profiler. Null means "not learned": the engine uses profile DIA/peak.
 */
@Serializable
data class AimiKineticsSnapshot(
    val effectiveDiaHours: Double? = null,
    val effectivePeakMinutes: Double? = null,
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
    /**
     * IOB array computed with learned (PKPD) kinetics, when available.
     * Empty means the shell did not compute it; the adapter passes null
     * and the engine falls back to the standard IOB array.
     */
    val pkpdIobHistory: List<IobTotal> = emptyList(),
)

@Serializable
data class AimiMealSnapshot(
    val cobG: TimedValue<Double>,
    val lastCarbsG: TimedValue<Double>,
    /** Slope from max deviation, used by the SMB decision. */
    val slopeFromMaxDeviation: TimedValue<Double> = TimedValue.Missing("not captured"),
    /** Slope from min deviation, used by the SMB decision. */
    val slopeFromMinDeviation: TimedValue<Double> = TimedValue.Missing("not captured"),
    /** Timestamp of the last carbs, epoch ms. */
    val lastCarbTimeMs: Long? = null,
    /** Timestamp of the last bolus, epoch ms. Null means not captured. */
    val lastBolusTimeMs: Long? = null,
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
 * Config frozen for this tick. The 102 typed config keys from the M1 registry
 * (`_docs/kmp/generated/m1-config-keys.csv`, role=config) are carried in [values].
 * Keys with role=persist are engine/effect state, not config (see AimiCausalState).
 */
@Serializable
data class AimiConfigSnapshot(
    val schemaVersion: Int,
    val values: AimiConfigValues = AimiConfigValues(),
)

/**
 * Typed config values frozen for one tick.
 *
 * Generated from `_docs/kmp/generated/m1-config-keys.csv` (102 keys with role=config).
 * Keys with role=persist are intentionally excluded: they are engine/effect state
 * stored in prefs, not configuration (see AimiCausalState).
 *
 * Field names are camelCase derivations of the key names. Defaults match the
 * key definitions in `:core:keys` and `:plugins:aps:...:keys`.
 */
@Serializable
data class AimiConfigValues(
    val pkpdLastPeakGovConsoleEchoed: String = "",
    val pkpdLastPeakGovLogLine: String = "",
    val pregnancyDueDateString: String = "",
    val auditorEnabled: Boolean = false,
    val physioAssistantEnable: Boolean = false,
    val basalChannelSafetyGuards: Boolean = true,
    val basalProjectedError: Boolean = true,
    val basalSlewLimitEnabled: Boolean = true,
    val basalTerminalInvariants: Boolean = true,
    val contextEnabled: Boolean = false,
    val diaGovernorEnabled: Boolean = true,
    val effectiveIobReleaseEnabled: Boolean = true,
    val effortActivityProtection: Boolean = true,
    val enableBasal: Boolean = false,
    val enableStepsFromWatch: Boolean = false,
    val hyperDroppingExemptEnabled: Boolean = true,
    val intelligenceKineticsProfiler: Boolean = true,
    val intelligenceSingleLearnPath: Boolean = true,
    val intelligenceSnapshotExport: Boolean = true,
    val iobSurveillanceGuard: Boolean = true,
    val mealConfirmedEarlyRelease: Boolean = false,
    val nightGrowthEnabled: Boolean = true,
    val peakGovernorEnabled: Boolean = true,
    val pkpdEnabled: Boolean = true,
    val pkpdEndogenousReversion: Boolean = true,
    val pkpdHyperReversion: Boolean = true,
    val pkpdPragmaticReliefEnabled: Boolean = true,
    val pkpdStackAwareGuardB: Boolean = false,
    val predictionAuthorityEnabled: Boolean = true,
    val predictionAuthorityShadow: Boolean = true,
    val sensorConfidenceCgmFirst: Boolean = false,
    val straightLineTubeAdvisorEnabled: Boolean = false,
    val t3cAdaptiveBasalEnabled: Boolean = true,
    val t3cAutodriveBasalAuthority: Boolean = true,
    val t3cBrittleMode: Boolean = true,
    val t3cCfrdExacerbationMode: Boolean = false,
    val t3cCfrdMode: Boolean = true,
    val t3cHyperBasalFloor: Boolean = true,
    val t3cPhysioInformedEnabled: Boolean = true,
    val trajectoryGuardEnabled: Boolean = false,
    val undeclaredCobEnabled: Boolean = false,
    val unifiedReactivityEnabled: Boolean = true,
    val autoDriveActive: Boolean = true,
    val autoDriveAuthoritative: Boolean = true,
    val autodriveAggressiveSmbFloor: Boolean = false,
    val honeymoon: Boolean = false,
    val night: Boolean = false,
    val pregnancy: Boolean = false,
    val apsSmbMaxIob: Double = 3.0,
    val apsUseSmb: Boolean = false,
    val activityBasalCapFactor: Double = 180.0,
    val bFPrebolus: Double = 2.5,
    val bFPrebolus2: Double = 2.0,
    val cHO: Double = 50.0,
    val diaGovernorLearnedWeight: Double = 0.45,
    val dinnerPrebolus: Double = 2.5,
    val dinnerPrebolus2: Double = 2.0,
    val highBGMaxSMB: Double = 1.0,
    val highBg: Double = 180.0,
    val highCarbPrebolus: Double = 5.0,
    val highCarbPrebolus2: Double = 5.0,
    val hyperDeepDevMgdl: Double = 0.0,
    val hyperEstablishedDevMgdl: Double = 0.0,
    val lunchPrebolus: Double = 2.5,
    val lunchPrebolus2: Double = 2.0,
    val maxMultiplier: Double = 1.6,
    val maxSMB: Double = 1.0,
    val mealPrebolus: Double = 2.0,
    val nightGrowthMaxIobExtra: Double = 180.0,
    val pkpdBoundsDiaMaxH: Double = 24.0,
    val pkpdBoundsDiaMinH: Double = 4.0,
    val pkpdPragmaticReliefMinFactor: Double = 0.75,
    val pkpdStateDiaH: Double = 6.0,
    val priorityMaxIobExtraU: Double = 2.0,
    val priorityMaxIobFactor: Double = 1.20,
    val redCarpetRestoreThreshold: Double = 0.75,
    val smbTailDamping: Double = 0.85,
    val snackPrebolus: Double = 1.0,
    val t3cActivationThreshold: Double = 100.0,
    val t3cAggressiveness: Double = 95.0,
    val t3cAnticipationStrength: Double = 95.0,
    val t3cCfrdCobDelayMin: Double = 30.0,
    val t3cCfrdLgsFloorMgdl: Double = 95.0,
    val tDD7: Double = 40.0,
    val undeclaredCobMaxG: Double = 25.0,
    val autodrivePrebolus: Double = 1.0,
    val autodrivesmallPrebolus: Double = 0.1,
    val weight: Double = 50.0,
    val autodriveMaxBasal: Double = 1.0,
    val meal_modes_MaxBasal: Double = 1.0,
    val bFinterval: Int = 3,
    val dinnerinterval: Int = 3,
    val hCinterval: Int = 3,
    val highBGinterval: Int = 3,
    val lunchinterval: Int = 3,
    val nightGrowthAgeYears: Int = 14,
    val sleepinterval: Int = 3,
    val snackinterval: Int = 3,
    val mealinterval: Int = 3,
    val tuningContextSelection: String = "AUTO_BALANCE",
    val contextMode: String = "BALANCED",
    val nightGrowthEnd: String = "06:00",
    val nightGrowthStart: String = "22:00",
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
