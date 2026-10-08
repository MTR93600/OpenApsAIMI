package app.aaps.pump.medtrum.session

import app.aaps.pump.medtrum.code.ConnectionState
import app.aaps.pump.medtrum.comm.MedtrumLogger
import app.aaps.pump.medtrum.comm.MedtrumRecordSync
import app.aaps.pump.medtrum.comm.packets.ActivatePacket
import app.aaps.pump.medtrum.comm.packets.AuthorizePacket
import app.aaps.pump.medtrum.comm.packets.CancelBolusPacket
import app.aaps.pump.medtrum.comm.packets.CancelTempBasalPacket
import app.aaps.pump.medtrum.comm.packets.ClearPumpAlarmPacket
import app.aaps.pump.medtrum.comm.packets.GetDeviceTypePacket
import app.aaps.pump.medtrum.comm.packets.GetRecordPacket
import app.aaps.pump.medtrum.comm.packets.GetTimePacket
import app.aaps.pump.medtrum.comm.packets.MedtrumPacket
import app.aaps.pump.medtrum.comm.packets.NotificationPacket
import app.aaps.pump.medtrum.comm.packets.PrimePacket
import app.aaps.pump.medtrum.comm.packets.ResumePumpPacket
import app.aaps.pump.medtrum.comm.packets.SetBasalProfilePacket
import app.aaps.pump.medtrum.comm.packets.SetBolusPacket
import app.aaps.pump.medtrum.comm.packets.SetPatchPacket
import app.aaps.pump.medtrum.comm.packets.SetTempBasalPacket
import app.aaps.pump.medtrum.comm.packets.SetTimePacket
import app.aaps.pump.medtrum.comm.packets.SetTimeZonePacket
import app.aaps.pump.medtrum.comm.packets.StopPatchPacket
import app.aaps.pump.medtrum.comm.packets.SubscribePacket
import app.aaps.pump.medtrum.comm.packets.SynchronizePacket
import app.aaps.pump.medtrum.util.MedtrumTimeUtil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.math.abs

/**
 * Portable Medtrum session: connection/auth flow, command/response flow,
 * and high-level pump operations.
 *
 * Transposed from the Android `MedtrumService` (pump/medtrum). The Android
 * version is a Dagger `Service` with inner `State` classes that busy-wait on
 * `SystemClock`; this version drives the same packet sequence with coroutines:
 * each step sends a packet and suspends until `onIndication` completes it or
 * the timeout fires. The packet bytes, opcodes, timeouts, retry counts and
 * failure transitions are unchanged.
 *
 * Deliberately NOT ported (M3): auto-reconnect policy (needs plugin state),
 * AAPS notifications/RxBus, `PumpSync` history writes (via [MedtrumRecordSync]),
 * UI progress channels. The transport itself ([MedtrumBleTransport]) stays a
 * seam — M3 implements it per platform.
 *
 * All public operations are `suspend` and must be called from a single
 * coroutine context (the Android service serialized them on its scope).
 */
class MedtrumSession(
    private val transport: MedtrumBleTransport,
    val state: MedtrumSessionState,
    private val recordSync: MedtrumRecordSync = MedtrumRecordSync.NO_OP,
    private val logger: MedtrumLogger = MedtrumLogger.NO_OP,
    private val clock: MedtrumClock,
    private val timeUtil: MedtrumTimeUtil = MedtrumTimeUtil(clock::nowMillis),
    private val timeZoneOffsetMinutes: () -> Int = { 0 },
    /**
     * Suspends for the given millis. Defaults to [delay]; tests pass a
     * clock-advancing lambda for virtual-time verification of the
     * dose-proportional bolus wait.
     */
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) : MedtrumBleCallback {

    companion object {

        /** Timeouts mirror the Android companion (seconds). */
        const val COMMAND_DEFAULT_TIMEOUT_SEC: Long = 60
        const val COMMAND_SYNC_TIMEOUT_SEC: Long = 120
        const val COMMAND_CONNECTING_TIMEOUT_SEC: Long = 30

        private const val MAX_SEND_RETRIES = 3
    }

    /** Session phase. The Android inner states collapse to: auth flow, ready, command. */
    enum class Phase { IDLE, CONNECTING, READY }

    var phase: Phase = Phase.IDLE
        private set

    private val scope = CoroutineScope(SupervisorJob())

    private data class PendingPacket(
        val packet: MedtrumPacket,
        val deferred: CompletableDeferred<PacketResult>,
        var retryCount: Int = 0
    )

    private sealed interface PacketResult {
        data object Success : PacketResult
        data object Failed : PacketResult
        data object Timeout : PacketResult
    }

    private var pendingAuth: PendingPacket? = null
    private var pendingCommand: PendingPacket? = null
    private var authResult: CompletableDeferred<Boolean>? = null

    /** Release resources (mirrors Service.onDestroy). */
    fun close() {
        transport.setCallback(null)
        scope.cancel()
    }

    // ------------------------------------------------------------------
    // Connection / auth flow
    // ------------------------------------------------------------------

    /**
     * Connect and run the auth handshake.
     * Returns true when the session reaches READY (same outcome as the Android
     * AuthState → … → SubscribeState → ReadyState chain).
     */
    suspend fun connect(from: String): Boolean {
        if (phase == Phase.READY && state.connectionState == ConnectionState.CONNECTED) {
            logger.debug("PUMPCOMM", "connect: already connected")
            return true
        }
        if (phase == Phase.CONNECTING) {
            // Auth already in progress — the Android service rejects connect
            // attempts in transitional states (neither Idle nor Ready).
            logger.error("PUMPCOMM", "connect: auth already in progress ($from)")
            return false
        }
        transport.setCallback(this)
        state.connectionState = ConnectionState.CONNECTING
        phase = Phase.CONNECTING
        authResult = CompletableDeferred()

        val started = transport.connect(from, state.pumpSN)
        if (!started) {
            logger.error("PUMPCOMM", "connect: transport refused ($from)")
            state.connectionState = ConnectionState.DISCONNECTED
            phase = Phase.IDLE
            return false
        }
        // The auth flow is driven by onConnected; await its verdict.
        // Bound: 8 × the per-step timeout. The flow has 6 steps (each capped
        // at COMMAND_CONNECTING_TIMEOUT_SEC by authStep); the extra margin
        // covers a transport that accepts connect() but never calls back.
        return try {
            withTimeout(COMMAND_CONNECTING_TIMEOUT_SEC * 1000 * 8) { authResult!!.await() }
        } catch (e: TimeoutCancellationException) {
            logger.error("PUMPCOMM", "connect: auth flow timeout ($from)")
            disconnect("connect timeout")
            false
        } finally {
            authResult = null
        }
    }

    fun disconnect(from: String) {
        logger.debug("PUMPCOMM", "disconnect: $from")
        state.connectionState = ConnectionState.DISCONNECTING
        transport.disconnect(from)
        // onDisconnected completes the teardown
    }

    override fun onConnected() {
        logger.debug("PUMPCOMM", "<<<<< onConnected")
        scope.launch {
            val ok = try {
                runAuthFlow()
            } catch (e: Exception) {
                logger.error("PUMPCOMM", "auth flow crashed: ${e.message}")
                false
            }
            if (ok) {
                state.connectionState = ConnectionState.CONNECTED
                phase = Phase.READY
            } else {
                state.connectionState = ConnectionState.DISCONNECTED
                phase = Phase.IDLE
            }
            authResult?.complete(ok)
        }
    }

    override fun onDisconnected() {
        logger.debug("PUMPCOMM", "<<<<< onDisconnected")
        state.connectionState = ConnectionState.DISCONNECTED
        phase = Phase.IDLE
        pendingAuth?.deferred?.complete(PacketResult.Failed)
        pendingCommand?.deferred?.complete(PacketResult.Failed)
        pendingAuth = null
        pendingCommand = null
        authResult?.complete(false)
        // Note: auto-reconnect policy is M3 (needs plugin/pump state).
    }

    /**
     * The auth handshake, step for step like the Android states:
     * Authorize → GetDeviceType → GetTime → [SetTime → SetTimeZone] → Synchronize → Subscribe.
     */
    private suspend fun runAuthFlow(): Boolean {
        if (!authStep(AuthorizePacket(state, logger))) return false
        if (!authStep(GetDeviceTypePacket())) return false
        if (!authStep(GetTimePacket(state, timeUtil, logger))) return false
        if (abs(state.lastTimeReceivedFromPump - clock.nowMillis()) > 10_000) {
            logger.debug("PUMPCOMM", "runAuthFlow: pump clock drift too big, setting time")
            if (!authStep(SetTimePacket(state, timeUtil, logger))) return false
            if (!authStep(SetTimeZonePacket(state, timeZoneOffsetMinutes(), timeUtil, logger))) return false
        }
        if (!authStep(SynchronizePacket(state, timeUtil, logger))) return false
        if (!authStep(SubscribePacket())) return false
        return true
    }

    private suspend fun authStep(packet: MedtrumPacket): Boolean {
        val deferred = CompletableDeferred<PacketResult>()
        pendingAuth = PendingPacket(packet, deferred)
        transport.sendMessage(packet.getRequest())
        val result = awaitResult(deferred, COMMAND_CONNECTING_TIMEOUT_SEC)
        pendingAuth = null
        if (result != PacketResult.Success) {
            logger.debug("PUMPCOMM", "authStep failed: ${packet.opCode} -> $result")
            transport.disconnect("auth step $result")
            return false
        }
        return true
    }

    // ------------------------------------------------------------------
    // Command flow
    // ------------------------------------------------------------------

    /**
     * Send one command packet and await its response.
     * Mirrors `sendPacketAndGetResponse`: only in READY, timeout disconnects
     * back to IDLE, a failed response returns to READY with false.
     */
    suspend fun sendCommand(packet: MedtrumPacket, timeoutSec: Long = COMMAND_DEFAULT_TIMEOUT_SEC): Boolean {
        if (phase != Phase.READY) {
            logger.error("PUMPCOMM", "Send packet attempt when in phase: $phase")
            return false
        }
        val deferred = CompletableDeferred<PacketResult>()
        pendingCommand = PendingPacket(packet, deferred)
        transport.sendMessage(packet.getRequest())
        val result = awaitResult(deferred, timeoutSec)
        pendingCommand = null
        return when (result) {
            PacketResult.Success -> true
            PacketResult.Failed  -> false // Android: back to ReadyState
            PacketResult.Timeout -> {
                // Android: disconnect("Timeout") + toState(IdleState())
                disconnect("command timeout")
                state.connectionState = ConnectionState.DISCONNECTED
                phase = Phase.IDLE
                false
            }
        }
    }

    private suspend fun awaitResult(deferred: CompletableDeferred<PacketResult>, timeoutSec: Long): PacketResult {
        return try {
            withTimeout(timeoutSec * 1000) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            logger.debug("PUMPCOMM", "packet timeout after ${timeoutSec}s")
            PacketResult.Timeout
        }
    }

    override fun onIndication(data: ByteArray) {
        val pending = pendingAuth ?: pendingCommand
        if (pending == null) {
            logger.warn("PUMPCOMM", "onIndication with no pending packet")
            return
        }
        val packet = pending.packet
        if (packet.handleResponse(data)) {
            pending.deferred.complete(PacketResult.Success)
        } else if (packet.failed) {
            pending.deferred.complete(PacketResult.Failed)
        }
        // else: RESP_WAITING — keep waiting, like the Android states
    }

    override fun onNotification(data: ByteArray) {
        logger.debug("PUMPCOMM", "<<<<< onNotification")
        NotificationPacket(state, timeUtil, logger).handleNotification(data)
    }

    override fun onSendMessageError(reason: String, isRetryAble: Boolean) {
        logger.warn("PUMPCOMM", "onSendMessageError: $reason")
        val pending = pendingAuth ?: pendingCommand
        if (pending != null && pending.retryCount < MAX_SEND_RETRIES && isRetryAble) {
            pending.retryCount++
            transport.sendMessage(pending.packet.getRequest())
        } else {
            pending?.deferred?.complete(PacketResult.Failed)
            pendingAuth = null
            pendingCommand = null
            // Android: disconnect("onSendMessageError") + toState(IdleState())
            transport.disconnect("onSendMessageError")
            state.connectionState = ConnectionState.DISCONNECTED
            phase = Phase.IDLE
        }
    }

    // ------------------------------------------------------------------
    // High-level operations (transposed from MedtrumService)
    // ------------------------------------------------------------------

    /** Bolus outcome, mirroring the Android verdict inputs. */
    enum class BolusResult { DELIVERED, STOPPED, NOT_CONNECTED, ALREADY_IN_PROGRESS, SEND_FAILED, TIMEOUT }

    /**
     * Deliver a bolus. Mirrors `MedtrumService.setBolus` minus the AAPS
     * history writes (M3 via MedtrumRecordSync): reset verdict inputs first,
     * send the command, then await the pump-reported completion via
     * [waitForBolusProgress].
     */
    suspend fun setBolus(insulin: Double): BolusResult {
        // Reset the per-bolus verdict inputs FIRST (Android comment preserved in intent)
        state.bolusAmountDelivered = 0.0
        state.bolusStopped = false
        if (phase != Phase.READY || state.connectionState != ConnectionState.CONNECTED) {
            state.bolusErrorReason = "not_connected"
            return BolusResult.NOT_CONNECTED
        }
        if (!state.bolusDone) {
            state.bolusErrorReason = "already_in_progress"
            return BolusResult.ALREADY_IN_PROGRESS
        }
        if (insulin <= 0) {
            state.bolusErrorReason = "invalid_amount"
            return BolusResult.SEND_FAILED
        }
        state.bolusDone = false
        state.bolusErrorReason = null
        state.bolusStartTime = clock.nowMillis()
        state.bolusProgressLastTimeStamp = state.bolusStartTime
        state.bolusAmountToBeDelivered = insulin

        val sent = sendCommand(SetBolusPacket(insulin))
        if (!sent) {
            state.bolusDone = true
            state.bolusErrorReason = "send_failed"
            return BolusResult.SEND_FAILED
        }
        // The pump reports progress via notifications → handleBolusStatusUpdate.
        val progressOk = waitForBolusProgress()
        return when {
            !progressOk          -> {
                // bolusErrorReason is "communication_lost" (set by waitForBolusProgress)
                BolusResult.TIMEOUT
            }
            state.bolusStopped   -> BolusResult.STOPPED
            state.bolusDone       -> BolusResult.DELIVERED
            else                 -> {
                // Dose-proportional window expired without bolusDone.
                state.bolusErrorReason = "timeout"
                BolusResult.TIMEOUT
            }
        }
    }

    /**
     * Mirrors `MedtrumService.waitForBolusProgress`.
     *
     * Phase 1 — stall detection: while the bolus is neither done nor stopped,
     * require a progress notification at least every 20 s. On a stall, retry
     * the connection (up to 3 times); after the 3rd failed retry, disconnect
     * and report communication lost. Progress resets the retry counter, so a
     * slow but live bolus (0.1 U increments can be >20 s apart on the tail)
     * is not mistaken for a stall.
     *
     * Phase 2 — dose-proportional completion wait: the pump delivers ~1 U/min,
     * so wait until `bolusStartTime + insulin*60_000 + 1000` ms or `bolusDone`.
     * A fixed timeout (e.g. 60 s) would fire during normal delivery of any
     * bolus above ~1 U and risk a double dose on retry — hence this formula.
     *
     * Returns false only on communication loss (phase 1). A phase-2 expiry
     * without `bolusDone` is reported by the caller as TIMEOUT.
     */
    private suspend fun waitForBolusProgress(): Boolean {
        var communicationLost = false
        var connectionRetryCounter = 0
        var checkTime = state.bolusProgressLastTimeStamp

        while (!state.bolusStopped && !state.bolusDone && !communicationLost) {
            sleeper(100)
            if (state.bolusProgressLastTimeStamp > checkTime) {
                // Progress resumed → reset the consecutive-no-progress retry
                // count. Without this the counter only ever grows: on a slow
                // but LIVE bolus each gap burns a retry until the 3-strike
                // limit forces a false "communication stopped".
                checkTime = state.bolusProgressLastTimeStamp
                connectionRetryCounter = 0
            }
            if (clock.nowMillis() - checkTime > 20_000) {
                if (connectionRetryCounter < 3) {
                    logger.warn("PUMPCOMM", "No bolus progress for 20 seconds, retrying connection")
                    connect("retrying connection")
                    checkTime = clock.nowMillis()
                    connectionRetryCounter++
                } else {
                    communicationLost = true
                    state.bolusErrorReason = "communication_lost"
                    logger.warn("PUMPCOMM", "Retry connection failed, communication stopped")
                    disconnect("Communication stopped")
                }
            }
        }
        if (communicationLost) return false

        // Dose-proportional wait (Android-verbatim formula): ~60 s per unit + 1 s margin.
        val bolusDurationMs = (state.bolusAmountToBeDelivered * 60 * 1000).toLong()
        val expectedEnd = state.bolusStartTime + bolusDurationMs + 1000
        while (clock.nowMillis() < expectedEnd && !state.bolusDone && !state.bolusStopped) {
            sleeper(1000)
        }

        // Allow time for the notification packet with the new sequence number to arrive.
        sleeper(2000)
        // M3: loadEvents() after bolus (needs plugin/pump state).

        return true
    }

    /** Mirrors `MedtrumService.stopBolus` (retry loop, 30 s cap, 200 ms between retries). */
    suspend fun stopBolus(): Boolean {
        state.bolusErrorReason = "user_stop"
        if (phase != Phase.READY || state.connectionState != ConnectionState.CONNECTED) {
            state.bolusStopped = true
            return false
        }
        val deadline = clock.nowMillis() + 30_000
        var success = sendCommand(CancelBolusPacket())
        while (!success && clock.nowMillis() < deadline) {
            success = sendCommand(CancelBolusPacket())
            sleeper(200)
        }
        logger.debug("PUMPCOMM", "stopBolus success: $success")
        state.bolusStopped = true
        return success
    }

    /** Mirrors `MedtrumService.setTempBasal`. */
    suspend fun setTempBasal(absoluteRate: Double, durationInMinutes: Int): Boolean {
        var result = true
        if (state.tempBasalInProgress) {
            result = sendCommand(CancelTempBasalPacket(state, timeUtil, logger))
        }
        if (result) result = sendCommand(SetTempBasalPacket(state, logger, timeUtil, absoluteRate, durationInMinutes))
        return result
    }

    /** Mirrors `MedtrumService.cancelTempBasal`. */
    suspend fun cancelTempBasal(): Boolean =
        sendCommand(CancelTempBasalPacket(state, timeUtil, logger))

    /** Mirrors `MedtrumService.updateBasalsInPump` (profile as M2 pairs). */
    suspend fun updateBasalProfile(basals: List<Pair<Double, Int>>): Boolean {
        var result = true
        if (state.tempBasalInProgress) {
            result = sendCommand(CancelTempBasalPacket(state, timeUtil, logger))
        }
        val bytes = state.buildMedtrumProfileArray(basals) ?: return false
        if (result) result = sendCommand(SetBasalProfilePacket(state, logger, timeUtil, bytes))
        return result
    }

    /** Mirrors `MedtrumService.setUserSettings`. */
    suspend fun setUserSettings(): Boolean =
        sendCommand(SetPatchPacket(state, logger))

    /** Mirrors `MedtrumService.startPrime`. */
    suspend fun prime(): Boolean =
        sendCommand(PrimePacket())

    /** Mirrors `MedtrumService.startActivate` (profile bytes built by the caller). */
    suspend fun activate(basalProfile: ByteArray): Boolean =
        sendCommand(ActivatePacket(state, basalProfile, logger = logger))

    /** Mirrors `MedtrumService.deactivatePatch`. */
    suspend fun deactivatePatch(): Boolean {
        var result = true
        if (state.tempBasalInProgress) {
            result = sendCommand(CancelTempBasalPacket(state, timeUtil, logger))
        }
        if (result) result = syncRecords()
        if (result) result = sendCommand(StopPatchPacket(state, logger))
        return result
    }

    /**
     * Send a clear-alarm command with the given code.
     *
     * Lower-level than `MedtrumService.clearAlarms`: the Android version loads
     * events first, picks code 4 (hourly-max suspend) or 5 (daily-max suspend)
     * from the pump state, then resumes the pump and clears the alarm state.
     * Here the caller (M3) owns that policy — see [resumePump] — and passes
     * the code explicitly.
     */
    suspend fun clearAlarm(clearType: Int): Boolean =
        sendCommand(ClearPumpAlarmPacket(clearType))

    /** Mirrors `MedtrumService` resume after hourly/daily-max suspend. */
    suspend fun resumePump(): Boolean =
        sendCommand(ResumePumpPacket())

    /**
     * Pull history records from the pump.
     * Mirrors `MedtrumService.syncRecords`: walk the unseen sequence range,
     * tolerate one broken record, abort on the second failure or a timeout.
     * Parsed records reach [MedtrumRecordSync] via the packet (M1).
     */
    suspend fun syncRecords(): Boolean {
        logger.debug(
            "PUMPCOMM",
            "syncRecords: syncedSequenceNumber: ${state.syncedSequenceNumber}, currentSequenceNumber: ${state.currentSequenceNumber}"
        )
        var result = true
        var failureCount = 0
        if (state.syncedSequenceNumber < state.currentSequenceNumber) {
            for (sequence in (state.syncedSequenceNumber + 1)..state.currentSequenceNumber) {
                val packet = GetRecordPacket(state, sequence, recordSync, timeUtil, logger)
                result = sendCommand(packet, COMMAND_SYNC_TIMEOUT_SEC)
                if (!result && packet.failed) {
                    failureCount++
                    logger.error("PUMPCOMM", "Failed to sync record $sequence, failureCount: $failureCount")
                    if (failureCount >= 2) break
                    // else: broken record, try the next one (Android shows a notification here — M3)
                } else if (!result) {
                    break // timeout (already disconnected by sendCommand)
                }
            }
        }
        return result
    }

    /**
     * Mirrors `MedtrumService.updateTimeIfNeeded` (timezone part).
     * The Android compares `pumpTimeZoneOffset` to the device offset and pushes
     * SetTime + SetTimeZone when they differ.
     */
    suspend fun updateTimeIfNeeded(currentTimeZoneOffsetMinutes: Int): Boolean {
        var result = true
        if (state.pumpTimeZoneOffset != currentTimeZoneOffsetMinutes) {
            result = sendCommand(SetTimePacket(state, timeUtil, logger))
            if (result) result = sendCommand(SetTimeZonePacket(state, currentTimeZoneOffsetMinutes, timeUtil, logger))
        }
        return result
    }
}
