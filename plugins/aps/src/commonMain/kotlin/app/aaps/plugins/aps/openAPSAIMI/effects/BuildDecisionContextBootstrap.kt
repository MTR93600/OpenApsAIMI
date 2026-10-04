package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.ISF.CommandedIsf
import app.aaps.plugins.aps.openAPSAIMI.IsfSourceTelemetry
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorProfileFactorCache
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorProfileTickState
import app.aaps.plugins.aps.openAPSAIMI.orchestration.AimiLoopPhase
import app.aaps.plugins.aps.openAPSAIMI.orchestration.AimiLoopTelemetry
import app.aaps.plugins.aps.openAPSAIMI.orchestration.AimiTickContext
import app.aaps.plugins.aps.openAPSAIMI.physio.HormonitorStudyExporter
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiEmergencySos
import kotlin.math.abs

/** `aimiLocalHour()`. Wall clock, read at the night-limit line. */
internal fun interface AimiDecisionLocalHour {
    fun hour(): Int
}

/**
 * Per-tick shadow resets and the few fields this head writes after the context exists.
 * The Android body keeps the assignments. Nothing here is hoisted.
 */
internal interface AimiDecisionBootstrapState<C> {
    fun resetPerTickShadow(nowMs: Long)
    fun lastBgRiseFastNightMs(): Long
    fun setLastBgRiseFastNightMs(epochMs: Long)
    fun rememberAuditor(state: AuditorProfileTickState)
    fun forgetWCycle()
    fun rememberProfile(profile: OapsProfileAimi)
    fun rememberPending(decisionCtx: C)
}

/** Builds [AimiDecisionContext] at the line the reference builds it. The type stays Android. */
internal fun interface AimiDecisionContextFactory<C> {
    fun create(trigger: String, eventId: String): C
}

/** `logLearnersHealth`. The learner report stays Android. */
internal fun interface AimiDecisionLearnersHealth {
    fun log(rT: RT)
}

/** `hormonitorStudyExporter`, read where the phase is entered. */
internal fun interface AimiDecisionStudyExporter {
    fun current(): HormonitorStudyExporter?
}

/**
 * Decision transparency context, initial loop [RT], and the shadowed flat-BG flag after the delta override.
 */
internal data class AimiDecisionRtBootstrap<C>(
    val decisionCtx: C,
    val rT: RT,
    val flatBGsDetected: Boolean,
)

/**
 * `buildDecisionContextInitRtSosAndFlatShadow`.
 *
 * The night rate limit, the rise trigger, the auditor-off cache clear, and the flat-sensor override
 * decide here. SOS, the learner health report, and the context object stay ports.
 */
internal fun <C> decideDecisionContextInitRtSosAndFlatShadow(
    ctx: AimiTickContext,
    preferences: Preferences,
    consoleLog: MutableList<String>,
    consoleError: MutableList<String>,
    aapsLogger: AAPSLogger,
    nowMs: Long,
    hour: AimiDecisionLocalHour,
    state: AimiDecisionBootstrapState<C>,
    contexts: AimiDecisionContextFactory<C>,
    sos: AimiEmergencySos,
    learners: AimiDecisionLearnersHealth,
    study: AimiDecisionStudyExporter,
): AimiDecisionRtBootstrap<C> {
    state.resetPerTickShadow(ctx.currentTime)
    val eventId = "evt_${ctx.currentTime}"
    val trigger = run {
        val iobNow = ctx.iobDataArray.firstOrNull()?.iob ?: 0.0
        val bgNow = ctx.glucoseStatus.glucose
        val localHour = hour.hour()
        val isNight = localHour >= 22 || localHour <= 7
        val isBgRiseFast = ctx.glucoseStatus.delta > 5
        val nightBangBangBlock = isNight && isBgRiseFast && iobNow > 2.0 && bgNow < 100.0 &&
            (ctx.currentTime - state.lastBgRiseFastNightMs()) < 15 * 60_000L
        if (isBgRiseFast && isNight && iobNow > 2.0 && bgNow < 100.0) {
            val lastNight = state.lastBgRiseFastNightMs()
            if (lastNight == 0L || (ctx.currentTime - lastNight) >= 15 * 60_000L) {
                state.setLastBgRiseFastNightMs(ctx.currentTime)
            }
        }
        when {
            nightBangBangBlock -> {
                consoleLog.add(
                    "🚫 T6 NIGHT_RATE_LIMIT: BG_Rise_Fast bloqué (IOB=${aimiFmt2(iobNow)}U > 2.0 ET BG=${bgNow.toInt()} < 100 la nuit)",
                )
                "Routine_Cycle"
            }
            isBgRiseFast -> "BG_Rise_Fast"
            else -> "Routine_Cycle"
        }
    }
    val decisionCtx = contexts.create(trigger, eventId)
    state.rememberAuditor(
        AuditorProfileTickState().apply {
            profileStaticIsfMgdl = IsfSourceTelemetry.lastProfileStaticMgdl
            dynamicIsfMgdl = ctx.profile.variable_sens
            commandIsfMgdl = ctx.profile.sens
            commandPreFloorIsfMgdl = CommandedIsf.lastPreFloorMgdlPerU
            commandFloorMultiplier = IsfSourceTelemetry.lastCommandFloorMultiplier
            profileTargetMgdl = ctx.profile.target_bg
            tempTargetActive = ctx.profile.temptargetSet
            keyOn = preferences.get(BooleanKey.OApsAIMIAuditorProfileFactors)
            if (!preferences.get(BooleanKey.AimiAuditorEnabled)) AuditorProfileFactorCache.clear()
            proposal = AuditorProfileFactorCache.latest()
        },
    )
    val rT = RT(
        algorithm = APSResult.Algorithm.AIMI,
        runningDynamicIsf = ctx.dynIsfMode,
        timestamp = ctx.currentTime,
        consoleLog = consoleLog,
        consoleError = consoleError,
    )
    AimiLoopTelemetry.enterPhase(AimiLoopPhase.CONTEXT, study.current())
    if (ctx.extraDebug.isNotEmpty()) {
        rT.reason.append("${ctx.extraDebug}\n")
    }
    sos.evaluate(
        aapsLogger = aapsLogger,
        bg = ctx.glucoseStatus.glucose,
        delta = ctx.glucoseStatus.delta,
        iob = ctx.iobDataArray.firstOrNull()?.iob ?: 0.0,
        preferences = preferences,
        nowMs = nowMs,
    )
    learners.log(rT)
    state.forgetWCycle()
    state.rememberProfile(ctx.profile)
    val flatBGsDetected = if (ctx.flatBGsDetected && abs(ctx.glucoseStatus.delta) > 3.0) {
        consoleLog.add("⚠️ FLAT OVERRIDE: Delta=${ctx.glucoseStatus.delta} > 3.0 -> Sensor ALIVE.")
        false
    } else {
        ctx.flatBGsDetected
    }
    state.rememberPending(decisionCtx)
    return AimiDecisionRtBootstrap(decisionCtx, rT, flatBGsDetected)
}
