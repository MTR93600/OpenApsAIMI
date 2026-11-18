package app.aaps.plugins.aps.openAPSAIMI.context

import app.aaps.core.interfaces.dashboard.AimiGlassDashboardData.ActiveContext
import app.aaps.core.interfaces.dashboard.AimiGlassDashboardData.ContextKind

/**
 * The active contexts as one dashboard item: which one leads, and whether the loop really uses it.
 *
 * Pure, so the rule is tested without a plugin. The dashboard draws "the loop knows" from [ActiveContext.usedByLoop],
 * so that flag must never be true for a context the dosing does not read.
 */
object ActiveContextSummary {

    /** A tick older than this is not "the loop now": two missed runs and a little more. */
    const val LAST_TICK_MAX_AGE_MS = 15L * 60L * 1000L

    /**
     * Only these kinds change the dosing: [ContextSnapshot] and [ContextInfluenceEngine] read them. Travel,
     * cycle and free text are kept and shown, but the loop does nothing with them.
     */
    private val dosingKinds = setOf(
        ContextKind.HYPO_RECOVERY,
        ContextKind.ILLNESS,
        ContextKind.ACTIVITY,
        ContextKind.ALCOHOL,
        ContextKind.STRESS,
        ContextKind.MEAL_RISK,
        ContextKind.SLOW_CARB_MEAL,
    )

    /**
     * @param intents every stored context, active or not
     * @param moduleOn the context setting (`OApsAIMIContextEnabled`): off, the tick does not read contexts
     * @param aimiActive AIMI is the active algorithm
     * @param lastTickMs time of the last AIMI result, null when there is none
     * @return null when no context is active now
     */
    fun summarize(
        intents: Collection<ContextIntent>,
        moduleOn: Boolean,
        aimiActive: Boolean,
        lastTickMs: Long?,
        nowMs: Long,
    ): ActiveContext? {
        val active = intents.filter { it.isActiveAt(nowMs) }
        // The most important kind leads (enum order), then the one that lasts longest
        val leader = active.minWithOrNull(compareBy<ContextIntent>({ it.kind().ordinal }, { -it.endTimeMs })) ?: return null
        val kind = leader.kind()
        val tickSawIt = lastTickMs != null && nowMs - lastTickMs in 0L..LAST_TICK_MAX_AGE_MS && lastTickMs >= leader.startTimeMs
        return ActiveContext(
            kind = kind,
            count = active.size,
            endsAtMs = leader.endTimeMs,
            usedByLoop = kind in dosingKinds && moduleOn && aimiActive && tickSawIt,
        )
    }

    private fun ContextIntent.kind(): ContextKind = when (this) {
        is ContextIntent.HypoRecovery        -> ContextKind.HYPO_RECOVERY
        is ContextIntent.Illness             -> ContextKind.ILLNESS
        is ContextIntent.Activity            -> ContextKind.ACTIVITY
        is ContextIntent.Alcohol             -> ContextKind.ALCOHOL
        is ContextIntent.Stress              -> ContextKind.STRESS
        is ContextIntent.UnannouncedMealRisk -> ContextKind.MEAL_RISK
        is ContextIntent.SlowCarbMeal        -> ContextKind.SLOW_CARB_MEAL
        is ContextIntent.Travel              -> ContextKind.TRAVEL
        is ContextIntent.MenstrualCycle      -> ContextKind.CYCLE
        is ContextIntent.Custom              -> ContextKind.CUSTOM
    }
}
