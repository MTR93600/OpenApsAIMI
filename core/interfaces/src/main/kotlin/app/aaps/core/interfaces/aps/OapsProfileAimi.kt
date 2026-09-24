package app.aaps.core.interfaces.aps


import android.annotation.SuppressLint
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable


//@SuppressLint("UnsafeOptInUsageError")
@OptIn(InternalSerializationApi::class)
@Serializable
@kotlinx.serialization.json.JsonIgnoreUnknownKeys
data class OapsProfileAimi(
    var dia: Double, // AMA only
    var min_5m_carbimpact: Double, // AMA only
    var max_iob: Double,
    var max_daily_basal: Double,
    var max_basal: Double,
    var min_bg: Double,
    var max_bg: Double,
    var target_bg: Double,
    var carb_ratio: Double,
    var sens: Double,
    var autosens_adjust_targets: Boolean, // AMA only
    var max_daily_safety_multiplier: Double,
    var current_basal_safety_multiplier: Double,
    var high_temptarget_raises_sensitivity: Boolean,
    var low_temptarget_lowers_sensitivity: Boolean,
    var sensitivity_raises_target: Boolean,
    var resistance_lowers_target: Boolean,
    var adv_target_adjustments: Boolean,
    var exercise_mode: Boolean,
    var half_basal_exercise_target: Int,
    var maxCOB: Int,
    var skip_neutral_temps: Boolean,
    var remainingCarbsCap: Int,
    var enableUAM: Boolean,
    var A52_risk_enable: Boolean,
    var SMBInterval: Int,
    var enableSMB_with_COB: Boolean,
    var enableSMB_with_temptarget: Boolean,
    var allowSMB_with_high_temptarget: Boolean,
    var enableSMB_always: Boolean,
    var enableSMB_after_carbs: Boolean,
    var maxSMBBasalMinutes: Int,
    var maxUAMSMBBasalMinutes: Int,
    var bolus_increment: Double,
    var carbsReqThreshold: Int,
    var current_basal: Double,
    var temptargetSet: Boolean,
    var autosens_max: Double,
    var out_units: String,
    var lgsThreshold: Int?,
    //DynISF only
    var variable_sens: Double,
    var insulinDivisor: Int,
    var TDD: Double,
    var peakTime: Double,
    var futureActivity: Double,
    var sensorLagActivity: Double,
    var historicActivity: Double,
    var currentActivity: Double,
    /**
     * Lower bound the stress ISF floor puts on the sensitivity the dose really uses, mg/dL per U.
     *
     * `null` means "no floor", and that is the value on every tick where the signature does not hold,
     * where the opt-in key is off, or where no honest resting heart rate could be measured. The dose
     * side may only ever RAISE its sensitivity to this value, never lower it.
     *
     * Raising a sensitivity makes the dose smaller on every path that divides by it — the correction
     * line, the MPC proportional term, the prediction stage. It is **not** a guarantee on the legacy
     * neural refinement path, where the same number is an input FEATURE (`insulinEffect = iob *
     * sensitivity / insulinDivisor` feeds the trend indicator) and the model's answer is not monotone
     * in it. That path is bypassed today and its own change is clamped, but the guarantee is
     * arithmetic, not universal. See `WorkingIsf`.
     *
     * It is carried here, next to [sens], because the loop commands the sensitivity in one class and
     * sizes the dose in another, and the working sensitivity is rebuilt from [variable_sens] and the
     * PKPD fusion rather than from [sens]. See `StressIsfFloor` and `WorkingIsf`.
     */
    var stress_floor_isf_mgdl: Double? = null,
    /**
     * Commanded sensitivity of this tick **before** the profile-relative floor, mg/dL per U.
     *
     * The same number exported as `isf_pre_floor_mgdl`: what the dynamic chain asked for, after every
     * multiplier and before any floor. `null` when it is not a usable number.
     *
     * It is carried per tick, and not read from a diagnostic global, because it sizes a basal: the
     * meal-window boost of `BasalDecisionEngine` divides it by the dose-facing sensitivity. [sens] is
     * the wrong numerator there — it carries both the 0.5 x profile bound and the stress floor, so a
     * protection would make that basal larger.
     */
    var pre_floor_isf_mgdl: Double? = null
)