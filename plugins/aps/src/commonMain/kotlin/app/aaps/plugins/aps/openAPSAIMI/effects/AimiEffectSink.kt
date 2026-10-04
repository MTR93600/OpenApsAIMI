package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.RT

/**
 * Dose effects the common decision is allowed to ask for, at the moment the reference
 * would call them. The Android shell implements this and delegates to the current
 * `setTempBasal` and `applySmbUnits`. There is no iOS implementation: `APS` stays false.
 *
 * The shell does not replay a list. Each call happens where the reference calls.
 */
internal interface AimiEffectSink {
    fun setTempBasal(
        rate: Double,
        durationMin: Int,
        profile: OapsProfileAimi,
        rT: RT,
        currenttemp: CurrentTemp,
        overrideSafetyLimits: Boolean,
        forceExact: Boolean,
        adaptiveMultiplier: Double,
    ): RT

    fun applySmbUnits(rT: RT, requestedU: Double, owner: String)
}
