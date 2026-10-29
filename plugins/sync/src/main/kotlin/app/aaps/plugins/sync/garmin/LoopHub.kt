package app.aaps.plugins.sync.garmin

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.profile.Profile
import java.time.Instant

/** One plateau of temporary basal for Millimétré staircase (`t` = start ms, `p` = percent). */
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
     * Remaining temporary basal duration in whole minutes, or null if no active TBR / EB.
     * Used by Blueprint / Cockpit / Atelier watchfaces (`tbrMins`).
     */
    val temporaryBasalRemainingMinutes: Int?

    /**
     * Current glucose target in mg/dL for the watch (`target`):
     * last APS/AIMI `targetBG` (DetermineBasalAIMI2) when available, else active TT midpoint,
     * else profile rounded target.
     */
    val currentTargetMgdl: Double?

    /**
     * Active AIMI therapy mode short label for the watch (`FCL`, `DINNER`, `LUNCH`, …),
     * or null when none is armed. Detected from Careportal NOTE keywords (same rules as [Therapy]).
     */
    val activeTherapyMode: String?

    /**
     * Temporary basal staircase for Millimétré (`basalHistory`): consecutive
     * (startMs, percent) steps over [windowMs], collapsed when percent is unchanged.
     * Percent 100 = profile basal. ⚠️ ASYNC IMPACT: runBlocking + DB/TBR lookup.
     */
    fun temporaryBasalHistory(windowMs: Long = 3L * 60L * 60L * 1000L): List<BasalHistoryStep>

    /**
     * SMB boluses in the last [windowMs] for watch faces (`smbHistory`: `{t,u}`).
     * ⚠️ ASYNC IMPACT: runBlocking + bolus DB query.
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

    // mod Bolus and temp target
    /**
     * Gives a bolus asked for by the watch, limited by the bolus constraints (max bolus) like any
     * other bolus. Returns the amount sent to the pump after that limit, 0.0 when nothing is given.
     * The caller must refuse the request on a client and when no request key is set.
     *
     * The pump command is sent later, so a returned amount means "sent on", not "delivered".
     * [onNotDelivered] gets the reason when the command queue refuses or fails it after that, so the
     * caller can tell the user.
     */
    fun postBolus(bolus: Double, onNotDelivered: (String) -> Unit): Double

    /** Stores or cancels a temptarget. */
    fun postTempTarget(target: Double, duration: Int)
    // end mod

    /**
     * Activates an AIMI therapy mode via CarePortal NOTE (keyword only, e.g. "lunch", "fcl", "sport").
     * Duration belongs on the TherapyEvent.duration field — never embed minutes in the note text
     * ([Therapy] matches with note.contains and uses event.duration for the active window).
     * Keywords are detected by openAPSAIMI [Therapy] (`therapy.kt`).
     */
    fun postTherapyMode(keyword: String, durationMin: Int)

    /** Stores hear rate readings that a taken and averaged of the given interval. */
    fun storeHeartRate(
        samplingStart: Instant, samplingEnd: Instant,
        avgHeartRate: Int,
        device: String?
    )

    /** Stores steps count readings aggregated over multiple intervals. */
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
