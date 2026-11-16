package app.aaps.plugins.aps.openAPSAIMI.wcycle

import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.keys.AimiLongKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Stores the start time of a declared pain flare when the person switches it on.
 *
 * The AIMI plugin collects this on the live value of `BooleanKey.AimiEndometriosisPainFlare`, from
 * the moment it starts, so it does not wait for a loop run and works while endometriosis is off or the
 * loop is not running. A change from off to on stores now: switching the flare off and on again starts
 * a new flare with its whole duration. Off clears the start. The first value (the app starts) only
 * fills a missing start and keeps the start of a flare that is already running
 * ([EndometriosisFlareClock.startToStore]).
 *
 * The switch is read as a `StateFlow`, which can merge two changes that come very close together. If
 * an off and an on are merged, the old start stays and the new flare is shorter, never longer: the
 * flare only adds basal, so this is the safe side.
 *
 * Step S3b of the AIMI settings plan. Before this, the start was written from `EventPreferenceChange`,
 * which nothing in the app ever sends, so a fast off and on kept the old start.
 */
object EndometriosisFlareSwitchWatcher {

    /** Follows [flareSwitch] and stores the start time; emits the start stored after each value. */
    fun storeStarts(
        flareSwitch: Flow<Boolean>,
        preferences: Preferences,
        nowMs: () -> Long = ::aimiWallClockMs,
    ): Flow<Long> = flow {
        var previousOn: Boolean? = null
        flareSwitch.collect { flareOn ->
            val stored = preferences.get(AimiLongKey.EndometriosisFlareStartedAt)
            val start = if (previousOn == false && flareOn) nowMs() else EndometriosisFlareClock.startToStore(flareOn, stored, nowMs())
            if (start != stored) preferences.put(AimiLongKey.EndometriosisFlareStartedAt, start)
            previousOn = flareOn
            emit(start)
        }
    }
}
