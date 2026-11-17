package app.aaps.plugins.aps.openAPSAIMI.tpo

import app.aaps.core.data.model.RM
import app.aaps.core.interfaces.aps.AimiBoostControl
import app.aaps.core.interfaces.aps.AimiBoostStartResult
import app.aaps.core.interfaces.aps.AimiBoostState
import app.aaps.core.interfaces.aps.AimiBoostUnavailableReason
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.concurrent.aapsIoDispatcher
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.OpenAPSAIMIPlugin
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn

/**
 * The manual Boost behind [AimiBoostControl], on top of the TPO session machinery.
 *
 * The state is computed again every 30 s while a screen watches it, and right after a start or a cancel.
 * [start] checks every guard again and builds the plan from the values in force at that moment, so a
 * screen that shows an old state can never start a Boost the guards would refuse.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class TpoBoostController(
    private val tpo: TpoOrchestrator,
    private val activePlugin: ActivePlugin,
    private val loop: Loop,
    private val profileFunction: ProfileFunction,
    private val hardLimits: HardLimits,
    private val preferences: Preferences,
    private val aapsLogger: AAPSLogger,
) : AimiBoostControl {

    private val scope = CoroutineScope(SupervisorJob() + aapsIoDispatcher)
    private val refresh = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val ticker = flow {
        while (true) {
            emit(Unit)
            delay(REFRESH_MS)
        }
    }

    override val state: StateFlow<AimiBoostState> = merge(ticker, refresh)
        .map { computeSafely() }
        .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AimiBoostState())

    override suspend fun start(): AimiBoostStartResult {
        val nowMs = aimiWallClockMs()
        reason(nowMs)?.let { return AimiBoostStartResult.Refused(it) }
        val hardMaxBasal = hardLimits.maxBasal()
        val session = tpo.startManualBoost(nowMs) { TpoBoostPlan.build(preferences, hardMaxBasal) }
            ?: return AimiBoostStartResult.Refused(
                if (tpo.manualBoostActive(nowMs)) AimiBoostUnavailableReason.ALREADY_ACTIVE else AimiBoostUnavailableReason.AT_LIMIT
            )
        refresh.tryEmit(Unit)
        return AimiBoostStartResult.Started(remainingMinutes(session.expiresAtMs, nowMs))
    }

    override fun cancel(): Boolean {
        // Only a Boost: a protective session is never ended from here
        val session = tpo.currentSession()
        if (session == null || session.packId != TpoPackId.MANUAL_BOOST || !session.isRunning()) return false
        val reverted = tpo.revertNow()
        aapsLogger.info(LTag.APS, "TPO manual boost cancelled: $reverted")
        refresh.tryEmit(Unit)
        return reverted
    }

    /**
     * [compute], which never throws. A throw inside the state flow would end it, and the screen would keep
     * the last state for good: a Boost that ended still showing "Cancel boost" (reported 2026-11-17). On a
     * failure the state comes from the session file alone, and the next refresh tries again.
     */
    private suspend fun computeSafely(): AimiBoostState = try {
        compute()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        aapsLogger.error(LTag.APS, "TPO manual boost state failed", e)
        val nowMs = aimiWallClockMs()
        runningBoostState(nowMs) ?: AimiBoostState()
    }

    private suspend fun compute(): AimiBoostState {
        val nowMs = aimiWallClockMs()
        runningBoostState(nowMs)?.let { return it }
        val reason = reason(nowMs)
        return AimiBoostState(available = reason == null, unavailableReason = reason)
    }

    /** The state of a Boost that runs now, or null. Past its end time a session is not running, whatever its status. */
    private fun runningBoostState(nowMs: Long): AimiBoostState? {
        val session = tpo.currentSession() ?: return null
        if (session.packId != TpoPackId.MANUAL_BOOST || !session.isRunning() || session.expiresAtMs <= nowMs) return null
        return AimiBoostState(available = false, active = true, remainingMinutes = remainingMinutes(session.expiresAtMs, nowMs))
    }

    private suspend fun reason(nowMs: Long): AimiBoostUnavailableReason? = TpoBoostEligibility.check(
        isAimi = activePlugin.activeAPS is OpenAPSAIMIPlugin,
        loopClosed = loop.runningMode() == RM.Mode.CLOSED_LOOP,
        tick = tpo.lastBoostTick,
        nowMs = nowMs,
        targetMgdl = profileFunction.getProfile()?.getTargetMgdl(),
        activeSession = tpo.currentSession(),
    )

    private fun TpoSessionDocument.isRunning(): Boolean =
        status == TpoSessionStatus.ACTIVE || status == TpoSessionStatus.PENDING_LLM

    private fun remainingMinutes(expiresAtMs: Long, nowMs: Long): Int =
        ((expiresAtMs - nowMs + 59_999L) / 60_000L).toInt().coerceAtLeast(0)

    private companion object {

        const val REFRESH_MS = 30_000L
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
