package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.orchestration.AimiTickContext

/**
 * Android scratch reset and telemetry of the early tick.
 * The TDD adoption is decided here. A failed loop pulse keeps the tick going and logs the type.
 */
internal data class AimiEarlyTickOutcome(
    val originalProfile: OapsProfileAimi,
    val isExplicitAdvisorRun: Boolean,
    val tdd7P: Double,
    val tdd7Days: Double,
)

internal interface AimiEarlyTickCalls {
    fun beginInvocation()
    fun clearBolusCache()
    fun resetConsoles()
    fun loggerReady(): Boolean
    fun recordLoopPulse(nowMs: Long)
    fun logPulseFailed(typeName: String?, message: String?)
    fun resetEarlyScratch(ctx: AimiTickContext)
    fun appendDebug(line: String)
    fun hydrate(mealData: MealData)
    fun copyProfile(profile: OapsProfileAimi): OapsProfileAimi
    fun enterBootstrap()
}

internal fun decideEarlyDetermineBasalStages(
    ctx: AimiTickContext,
    preferences: Preferences,
    calls: AimiEarlyTickCalls,
): AimiEarlyTickOutcome {
    calls.beginInvocation()
    calls.clearBolusCache()
    calls.resetConsoles()
    if (calls.loggerReady()) {
        try {
            calls.recordLoopPulse(ctx.currentTime)
        } catch (t: Throwable) {
            // Never break determine_basal on telemetry.
            calls.logPulseFailed(t::class.simpleName, t.message)
        }
    }
    calls.resetEarlyScratch(ctx)

    if (ctx.extraDebug.isNotEmpty()) {
        calls.appendDebug(ctx.extraDebug)
    }

    calls.hydrate(ctx.mealData)

    val isExplicitAdvisorRun = preferences.get(BooleanKey.OApsAIMIMealAdvisorTrigger)
    val tdd7P = preferences.get(DoubleKey.OApsAIMITDD7)
    var tdd7Days = ctx.profile.TDD
    // `!isFinite()` first: the `tdd7Days.toFloat() != 0.0f` guards further down are TRUE for NaN,
    // so a NaN would enter those branches and make `basalaimi` (tdd7Days / weight) and
    // `ci` (450 / tdd7Days) NaN for the whole tick. No change for any finite value.
    if (!tdd7Days.isFinite() || tdd7Days == 0.0 || tdd7Days < tdd7P) tdd7Days = tdd7P

    val originalProfile = calls.copyProfile(ctx.profile)
    calls.enterBootstrap()

    return AimiEarlyTickOutcome(
        originalProfile = originalProfile,
        isExplicitAdvisorRun = isExplicitAdvisorRun,
        tdd7P = tdd7P,
        tdd7Days = tdd7Days,
    )
}
