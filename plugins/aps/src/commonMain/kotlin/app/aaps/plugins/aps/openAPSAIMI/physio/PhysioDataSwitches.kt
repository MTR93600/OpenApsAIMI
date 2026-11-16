package app.aaps.plugins.aps.openAPSAIMI.physio

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences

/**
 * The two switches of the physio card that keep a reading out of AIMI: "use sleep data"
 * ([BooleanKey.AimiPhysioSleepDataEnable]) and "use HRV data" ([BooleanKey.AimiPhysioHRVDataEnable]).
 * Both are on by default, and then every function here gives back what it was given.
 *
 * A switch that is off makes its reading absent, never a stand-in number. Every consumer already
 * handles an absent reading as neutral (no sleep debt, no HRV depression), so "off" is the state the
 * dose path sees on a phone with no watch.
 *
 * Applied where the readings enter AIMI, so no dosing path can miss it:
 * - the raw read of the physio run (`PhysioPipeline`), so the features, the baseline, the context
 *   and the language model step never see the reading;
 * - the live health snapshot (`HealthContextRepository`), at its inputs and on the snapshot it gives;
 * - the stored context, each time a dosing path reads it (`AIMIInsulinDecisionAdapterMTR`), so a
 *   context stored before the switch was turned off (it is valid for 20 hours) does not keep acting;
 * - the HRV of the thermal recovery proxy (the platform data sources).
 *
 * Step S3b of the AIMI settings plan (owner decision 2026-11-15). Before, nothing read the two keys.
 */
data class PhysioDataSwitches(val sleep: Boolean, val hrv: Boolean) {

    /** Both switches on: every function here gives back what it was given. */
    val allOn: Boolean get() = sleep && hrv

    /** [data], or null when the sleep switch is off. */
    fun sleepOrNull(data: SleepDataMTR?): SleepDataMTR? = if (sleep) data else null

    /** [samples], or none when the HRV switch is off. */
    fun hrvOrEmpty(samples: List<HRVDataMTR>): List<HRVDataMTR> = if (hrv) samples else emptyList()

    /** The raw read without the readings of a switch that is off. */
    fun applyTo(raw: RawPhysioDataMTR): RawPhysioDataMTR =
        if (allOn) raw else raw.copy(sleep = sleepOrNull(raw.sleep), hrv = hrvOrEmpty(raw.hrv))

    /** The features with the values of a switch that is off at 0, the value of "no data". */
    fun applyTo(features: PhysioFeaturesMTR): PhysioFeaturesMTR {
        if (allOn) return features
        var result = features
        if (!sleep) {
            result = result.copy(
                sleepDurationHours = 0.0,
                sleepEfficiency = 0.0,
                sleepFragmentation = 0.0,
                sleepQualityScore = 0.0,
                deepSleepPercent = 0.0,
            )
        }
        if (!hrv) result = result.copy(hrvMeanRMSSD = 0.0, hrvTrend = 0.0, hrvVariability = 0.0)
        return result
    }

    /**
     * The stored context without the verdicts of a switch that is off.
     *
     * When that takes away a poor sleep or low HRV verdict, the state and the recommendations are
     * classified again from the flags that are left, with the same rule the engine uses
     * ([AIMIPhysioContextEngineMTR.classify]). Otherwise they are kept: a context with no such verdict
     * must not change state just because a switch is off. Its time and its confidence are kept.
     */
    fun applyTo(context: PhysioContextMTR): PhysioContextMTR {
        if (allOn) return context
        val poorSleep = context.poorSleepDetected && sleep
        val hrvDepressed = context.hrvDepressed && hrv
        val hrvZ = if (hrv) context.hrvDeviationZ else 0.0
        val stripped = context.copy(
            poorSleepDetected = poorSleep,
            hrvDepressed = hrvDepressed,
            sleepDeviationZ = if (sleep) context.sleepDeviationZ else 0.0,
            hrvDeviationZ = hrvZ,
            features = context.features?.let(::applyTo),
        )
        if (poorSleep == context.poorSleepDetected && hrvDepressed == context.hrvDepressed) return stripped
        val classification = AIMIPhysioContextEngineMTR.classify(
            poorSleep = poorSleep,
            hrvDepressed = hrvDepressed,
            rhrElevated = context.rhrElevated,
            activityReduced = context.activityReduced,
            hrvZ = hrvZ,
            rhrZ = context.rhrDeviationZ,
        )
        return stripped.copy(
            state = classification.state,
            recommendReduceBasal = classification.reduceBasal,
            recommendReduceSMB = classification.reduceSMB,
            recommendIncreaseISF = classification.increaseISF,
        )
    }

    /**
     * The snapshot without the values of a switch that is off, set to what the repository gives when
     * the reading is missing (0 sleep debt and efficiency, no sleep session, 0 HRV and no depression).
     */
    fun applyTo(snapshot: HealthContextSnapshot): HealthContextSnapshot {
        if (allOn) return snapshot
        var result = snapshot
        if (!sleep) result = result.copy(sleepDebtMinutes = 0, sleepEfficiency = 0.0, hcSleepSessionActive = false)
        if (!hrv) result = result.copy(hrvRmssd = 0.0, hrvDepression = 0.0)
        return result
    }

    companion object {

        /** Both switches on: the default, and nothing changes. */
        val ALL_ON: PhysioDataSwitches = PhysioDataSwitches(sleep = true, hrv = true)

        /** The switches as the person set them now. */
        fun read(preferences: Preferences): PhysioDataSwitches = PhysioDataSwitches(
            sleep = preferences.get(BooleanKey.AimiPhysioSleepDataEnable),
            hrv = preferences.get(BooleanKey.AimiPhysioHRVDataEnable),
        )
    }
}
