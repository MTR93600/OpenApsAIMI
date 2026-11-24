package app.aaps.plugins.aps.openAPSAIMI.orchestration

import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.utils.takeWholeChars
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2

/**
 * Builds safe [RT] responses when a loop tick cannot complete normally.
 * Never throws — used to keep the APS process alive after lock contention or unexpected errors.
 *
 * A lock skip commands nothing: another tick is running and decides. A tick that threw commands
 * the temp basal of [AimiSafeHoldBasal] (profile basal, or zero when glucose is low or falling to
 * the hypo threshold), never an SMB.
 *
 * Portable note: frame-level stack traces come from [aimiThrowableFrames]
 * (real frames on JVM/Android, empty on iOS where Kotlin/Native has no stack-trace API).
 */
object AimiLoopTickRecovery {

    private const val MAX_CONSOLE_TAIL = 64

    private val TELEMETRY_ELIDE = setOf(
        "AimiLoopTelemetry",
        "AimiLoopTickRecovery",
        "AimiDetermineBasalTickOrchestrator",
        "AimiLoopGate",
    )

    fun skippedPriorTickStillRunning(ctx: AimiTickContext): RT {
        val log = mutableListOf(
            "AIMI tick skipped: prior determine_basal still running (exclusive lock timeout)",
        )
        return minimalRt(
            ctx = ctx,
            log = log,
            errors = mutableListOf(),
            reasonLine = "AIMI safe skip: prior tick still running; no insulin change this cycle.",
        )
    }

    fun safeResultAfterUnhandledError(
        ctx: AimiTickContext,
        error: Throwable,
        consoleLog: List<String>?,
        consoleError: List<String>?,
    ): RT {
        val errName = error::class.simpleName ?: "Throwable"
        val msg = error.message?.takeWholeChars(200).orEmpty()
        val phase = AimiLoopTelemetry.currentLoopPhase
        val location = primaryAimiFrame(error)
        val phaseHint = phase.safeHoldHint()
        val mergedLog = (consoleLog?.takeLast(MAX_CONSOLE_TAIL)?.toMutableList() ?: mutableListOf()).apply {
            add(
                "AIMI tick recovered: $errName phase=${phase.name} ($phaseHint)" +
                    (location?.let { " at $it" } ?: "") +
                    if (msg.isNotEmpty()) " — $msg" else "",
            )
        }
        val mergedErr = (consoleError?.toMutableList() ?: mutableListOf()).apply {
            add(
                "Unhandled tick error ($errName) in ${phase.name} ($phaseHint)" +
                    (location?.let { " at $it" } ?: "") +
                    if (msg.isNotEmpty()) ": $msg" else "",
            )
            stackTraceSummary(error).forEach { add(it) }
        }
        val reasonLocation = location?.let { " @ $it" }.orEmpty()
        val command = holdBasal(ctx)
        val action = when (command) {
            is AimiSafeHoldBasal.Command.Temp -> "${command.why} ${aimiFmt2(command.rateUPerH)} U/h for ${command.durationMinutes} min, no SMB"
            is AimiSafeHoldBasal.Command.Keep -> "${command.why}, no SMB"
        }
        val rt = minimalRt(
            ctx = ctx,
            log = mergedLog,
            errors = mergedErr,
            reasonLine = "AIMI safe hold [${phase.name}: $phaseHint$reasonLocation]: $errName — $action.",
        )
        if (command is AimiSafeHoldBasal.Command.Temp) {
            rt.rate = command.rateUPerH
            rt.duration = command.durationMinutes
        }
        return rt
    }

    /**
     * The temp basal of this safe hold, see [AimiSafeHoldBasal]. A failure here gives "no change",
     * the behaviour before 2026-11-20: this already runs after an error and must not add one.
     */
    internal fun holdBasal(ctx: AimiTickContext): AimiSafeHoldBasal.Command =
        try {
            val glucoseDate = ctx.glucoseStatus.date
            AimiSafeHoldBasal.decide(
                bg = ctx.glucoseStatus.glucose,
                delta = ctx.glucoseStatus.delta,
                shortAvgDelta = ctx.glucoseStatus.shortAvgDelta,
                glucoseAgeMinutes = if (glucoseDate > 0L) (ctx.currentTime - glucoseDate) / 60_000.0 else null,
                // The smaller of the value at tick start and now: the tick may have raised it (up to
                // x1.8 for the gestational autopilot) before it threw, and a protective cut is kept.
                profileBasal = listOfNotNull(ctx.basalAtTickStart, ctx.profile.current_basal).minOrNull() ?: Double.NaN,
                minBg = ctx.profile.min_bg,
                lgsThreshold = ctx.profile.lgsThreshold,
                currentRate = ctx.currentTemp.rate,
                currentRemainingMinutes = ctx.currentTemp.duration,
            )
        } catch (e: Exception) {
            AimiSafeHoldBasal.Command.Keep("hold basal not computed (${e::class.simpleName})")
        }

    /**
     * First AIMI frame outside telemetry/recovery wrappers — identifies the calculation site.
     * Null on platforms without stack-trace access.
     */
    internal fun primaryAimiFrame(error: Throwable): String? =
        aimiThrowableFrames(error).firstOrNull { frame -> isAimiFrame(frame) }

    internal fun stackTraceSummary(error: Throwable, maxFrames: Int = 3): List<String> {
        val frames = aimiThrowableFrames(error)
        val aimiFrames = frames.filter { isAimiFrame(it) }.take(maxFrames)
        val selected = if (aimiFrames.isNotEmpty()) aimiFrames else frames.take(1)
        return selected.map { "  at $it" }
    }

    private fun isAimiFrame(frame: String): Boolean =
        frame.contains("openAPSAIMI") &&
            TELEMETRY_ELIDE.none { frame.contains(it) }

    private fun minimalRt(
        ctx: AimiTickContext,
        log: MutableList<String>,
        errors: MutableList<String>,
        reasonLine: String,
    ): RT =
        RT(
            algorithm = APSResult.Algorithm.AIMI,
            runningDynamicIsf = ctx.dynIsfMode,
            timestamp = ctx.currentTime,
            bg = ctx.glucoseStatus.glucose,
            deliverAt = ctx.currentTime,
            IOB = ctx.iobDataArray.firstOrNull()?.iob,
            COB = ctx.mealData.mealCOB,
            reason = StringBuilder(reasonLine),
            consoleLog = log,
            consoleError = errors,
        )
}
