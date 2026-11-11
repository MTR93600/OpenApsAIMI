package app.aaps.plugins.sync.garmin

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.profile.Profile
import java.time.Instant

/** One step of temporary basal for the Millimétré staircase (`t` = start in ms, `p` = percent). */
data class BasalHistoryStep(val t: Long, val p: Int)

/** One SMB for watch logs (`t` = epoch ms, `u` = insulin units). */
data class SmbHistoryStep(val t: Long, val u: Double)

/** Abstraction from all the functionality we need from the AAPS app. */
interface LoopHub {

    /** Returns the active insulin profile. */
    val currentProfile: Profile?

    /** Returns the name of the active insulin profile. */
    val currentProfileName: String

    /** Returns the glucose unit (mg/dl or mmol/l) as selected by the user. */
    val glucoseUnit: GlucoseUnit

    /** Returns the remaining bolus insulin on board. */
    val insulinOnboard: Double

    /** Returns the basal insulin on board. */
    val insulinBasalOnboard: Double

    /** Returns the remaining carbs on board. */
    val carbsOnboard: Double?

    /** Returns true if the pump is connected. */
    val isConnected: Boolean

    /** Returns true if the current profile is set of a limited amount of time. */
    val isTemporaryProfile: Boolean

    /** Returns the factor by which the basal rate is currently raised (> 1) or lowered (< 1). */
    val temporaryBasal: Double

    /**
     * Remaining temporary basal duration in whole minutes, or null if no TBR or extended bolus is active.
     * Used by the Blueprint / Cockpit / Atelier watchfaces (`tbrMins`).
     */
    val temporaryBasalRemainingMinutes: Int?

    /**
     * Current glucose target in mg/dL for the watch (`target`):
     * the target of the last APS run when there is one, else the middle of the active temporary
     * target, else the rounded profile target.
     */
    val currentTargetMgdl: Double?

    /**
     * Short label of the active AIMI therapy mode for the watch (`FCL`, `DINNER`, `COFFEE`, ...),
     * or null when none is active. Read from Careportal NOTE keywords, see [GarminActiveTherapyMode].
     */
    val activeTherapyMode: String?

    /**
     * Temporary basal staircase for Millimétré (`basalHistory`): steps of (start ms, percent) over
     * [windowMs], one new step only when the percent changes. Percent 100 = profile basal.
     * ⚠️ ASYNC IMPACT: runBlocking + TBR lookup.
     */
    fun temporaryBasalHistory(windowMs: Long = 3L * 60L * 60L * 1000L): List<BasalHistoryStep>

    /**
     * SMBs in the last [windowMs] for Millimétré / Téléscripteur (`smbHistory`: `{t,u}`).
     * ⚠️ ASYNC IMPACT: runBlocking + bolus database query.
     */
    fun smbHistory(windowMs: Long = 3L * 60L * 60L * 1000L): List<SmbHistoryStep>

    /** Returns the lower bound of the target glucose range. */
    val lowGlucoseMark: Double

    /** Returns the upper bound of the target glucose range. */
    val highGlucoseMark: Double

    /** Tells the loop algorithm that the pump is physically connected. */
    fun connectPump()

    /** Tells the loop algorithm that the pump will be physically disconnected
     *  for the given number of minutes. */
    fun disconnectPump(minutes: Int)

    /** Retrieves the glucose values starting at from. */
    fun getGlucoseValues(from: Instant, ascending: Boolean): List<GV>

    /** Notifies the system that carbs were eaten and stores the value. */
    fun postCarbs(carbohydrates: Int)

    /**
     * Gives a bolus asked for by the watch, limited by the bolus constraints (max bolus) like any
     * other bolus. Returns the amount sent to the pump after that limit, 0.0 when nothing is given.
     * The caller must refuse the request on a client and when no request key is set.
     *
     * The pump command is sent later, so a returned amount means "sent on", not "delivered".
     * [onNotDelivered] gets the reason when AAPS refuses or fails it after that (for example the loop
     * is suspended), so the caller can tell the user.
     */
    fun postBolus(bolus: Double, onNotDelivered: (String) -> Unit): Double

    /** Stores or cancels a temporary target. A target or duration of zero cancels the current one. */
    fun postTempTarget(target: Double, duration: Int)

    /**
     * Activates an AIMI therapy mode via a Careportal NOTE (keyword only, e.g. "lunch", "fcl", "sport").
     * Duration belongs on the TherapyEvent.duration field. AIMI `Therapy` matches with `note.contains`
     * and uses `event.duration` for the active window.
     */
    fun postTherapyMode(keyword: String, durationMin: Int)

    /** Stores hear rate readings that a taken and averaged of the given interval. */
    fun storeHeartRate(
        samplingStart: Instant, samplingEnd: Instant,
        avgHeartRate: Int,
        device: String?
    )

    /** Stores step counts that the watch summed over several intervals. */
    fun storeStepsCount(
        samplingStart: Instant,
        samplingEnd: Instant,
        steps5min: Int,
        steps10min: Int,
        steps15min: Int,
        steps30min: Int,
        steps60min: Int,
        steps180min: Int,
        device: String?,
    )
}
