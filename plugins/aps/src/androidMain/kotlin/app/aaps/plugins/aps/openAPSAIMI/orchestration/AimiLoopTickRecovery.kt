package app.aaps.plugins.aps.openAPSAIMI.orchestration

import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.RT

/**
 * Builds safe [RT] responses when a loop tick cannot complete normally.
 * Never throws — used to keep the APS process alive after lock contention or unexpected errors.
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
        val msg = error.message?.take(200).orEmpty()
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
        return minimalRt(
            ctx = ctx,
            log = mergedLog,
            errors = mergedErr,
            reasonLine = "AIMI safe hold [${phase.name}: $phaseHint$reasonLocation]: $errName — no insulin change this tick.",
        )
    }

    /**
     * First AIMI stack frame outside telemetry/recovery wrappers — identifies the calculation site.
     */
    internal fun primaryAimiFrame(error: Throwable): String? =
        error.stackTrace.firstOrNull { frame -> isAimiFrame(frame) }?.let(::formatFrame)

    internal fun stackTraceSummary(error: Throwable, maxFrames: Int = 3): List<String> {
        val aimiFrames = error.stackTrace.filter { isAimiFrame(it) }.take(maxFrames)
        val frames = if (aimiFrames.isNotEmpty()) aimiFrames else error.stackTrace.take(1)
        return frames.map { "  at ${formatFrame(it)}" }
    }

    private fun isAimiFrame(frame: StackTraceElement): Boolean =
        frame.className.contains("openAPSAIMI") &&
            TELEMETRY_ELIDE.none { frame.className.contains(it) }

    private fun formatFrame(frame: StackTraceElement): String {
        val simpleClass = frame.className.substringAfterLast('.')
        return "$simpleClass.${frame.methodName}:${frame.lineNumber}"
    }

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
