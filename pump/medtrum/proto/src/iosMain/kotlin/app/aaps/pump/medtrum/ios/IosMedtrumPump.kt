package app.aaps.pump.medtrum.ios

import app.aaps.core.data.pump.defs.ManufacturerType
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.pump.defs.fillFor
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.DetailedBolusInfoStorage
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.pump.PumpProfile
import app.aaps.core.interfaces.pump.PumpRate
import app.aaps.core.interfaces.pump.PumpInsulin
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.pump.TemporaryBasalStorage
import app.aaps.core.interfaces.pump.actions.CustomAction
import app.aaps.core.interfaces.pump.actions.CustomActionType
import app.aaps.core.interfaces.queue.CustomCommand
import app.aaps.pump.medtrum.code.ConnectionState
import app.aaps.pump.medtrum.comm.MedtrumLogger
import app.aaps.pump.medtrum.comm.enums.BasalType
import app.aaps.pump.medtrum.comm.enums.MedtrumPumpState
import app.aaps.pump.medtrum.session.MedtrumBleTransport
import app.aaps.pump.medtrum.session.MedtrumClock
import app.aaps.pump.medtrum.session.MedtrumSession
import app.aaps.pump.medtrum.session.MedtrumSessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

/**
 * Omnipod Medtrum [Pump] for iOS, driving the M2 [MedtrumSession].
 *
 * Lifecycle: [connect] → transport connects by SN → [MedtrumSession] runs the
 * auth flow → [Phase.READY]. All therapeutic methods go through [requireReady]
 * first.
 *
 * **Safety guard**: [isReady] (session `Phase.READY` + `ConnectionState.CONNECTED`)
 * is checked at the top of every method that sends bytes to the pump. Not ready
 * → fail-closed [PumpEnactResult] (`success=false`), zero bytes on the radio.
 *
 * Not supported (mirrors Android): percent TBR, extended bolus.
 * Threading: therapeutic commands are serialized by [commandMutex].
 */
class IosMedtrumPump(
    private val transport: MedtrumBleTransport,
    val state: MedtrumSessionState,
    private val clock: MedtrumClock,
    private val textResolver: TextResolver,
    /** The loop reads the pump history from here. The driver writes every bolus, temp basal and alarm to it. */
    pumpSync: PumpSync,
    detailedBolusInfoStorage: DetailedBolusInfoStorage,
    temporaryBasalStorage: TemporaryBasalStorage,
    /**
     * Scope for the work the driver starts itself (connect, stop bolus). The owner creates it and
     * cancels it. Cancel it together with [close].
     */
    private val scope: CoroutineScope,
    private val store: IosMedtrumStateStore? = null,
    private val logger: MedtrumLogger = MedtrumLogger.NO_OP,
) : Pump {

    private val history = MedtrumPumpSyncHistory(
        pumpSync = pumpSync,
        detailedBolusInfoStorage = detailedBolusInfoStorage,
        temporaryBasalStorage = temporaryBasalStorage,
        deviceType = { state.deviceType },
        serialProvider = { state.pumpSN.toString(radix = 16) },
        nowMillis = clock::nowMillis,
        logger = logger,
    ).also { state.recordSync = it }

    private val session = MedtrumSession(
        transport = transport,
        state = state,
        recordSync = history,
        logger = logger,
        clock = clock,
        // Medtrum basal schedules run on the pump local time. The clock knows the phone offset.
        timeZoneOffsetMinutes = { clock.utcOffsetMinutes(clock.nowMillis()) },
    ).also {
        transport.setCallback(it)
        it.onStateChanged = ::onSessionData
    }

    private val commandMutex = Mutex()
    private var busy = false

    init {
        store?.load(state)
    }

    /** Release the session. The owner cancels [scope] itself. */
    fun close() {
        session.close()
    }

    // -- Safety guard --------------------------------------------------------

    /** True only when the session completed auth AND the BLE link is up. */
    fun isReady(): Boolean =
        session.phase == MedtrumSession.Phase.READY &&
            state.connectionState == ConnectionState.CONNECTED

    // -- Pump identity -------------------------------------------------------

    override fun manufacturer(): ManufacturerType = ManufacturerType.Medtrum
    override fun model(): PumpType = PumpType.MEDTRUM_NANO
    // Same as Android: the serial number as upper case hex.
    override fun serialNumber(): String = state.pumpSN.toString(radix = 16).uppercase()

    // Filled from the pump type, like Android. Building it by hand left wrong defaults, for example
    // a PERCENT temp basal style, which makes the safety checks skip the absolute limit.
    override val pumpDescription: PumpDescription = PumpDescription().fillFor(PumpType.MEDTRUM_NANO)

    override val isFakingTempsByExtendedBoluses: Boolean = false

    // -- State ----------------------------------------------------------------

    // Android: the pump has a serial number and a patch that was filled at least once.
    override fun isConfigured(): Boolean =
        state.pumpSN != 0L && state.pumpState > MedtrumPumpState.EJECTED

    // Android: from the stored patch state, NOT from the BLE link. The loop must still run when
    // the link is down.
    override fun isInitialized(): Boolean =
        isConfigured() && state.pumpState < MedtrumPumpState.STOPPED

    // Android: only ACTIVE and ACTIVE_ALT can deliver. Everything else counts as suspended.
    override fun isSuspended(): Boolean =
        state.pumpState < MedtrumPumpState.ACTIVE || state.pumpState > MedtrumPumpState.ACTIVE_ALT

    override fun isBusy(): Boolean = busy

    // Android: without an active patch we say "connected", so the queue does not start connects.
    override fun isConnected(): Boolean =
        if (!isInitialized()) true else state.connectionState == ConnectionState.CONNECTED

    override fun isConnecting(): Boolean = state.connectionState == ConnectionState.CONNECTING
    override fun isHandshakeInProgress(): Boolean = session.phase == MedtrumSession.Phase.CONNECTING

    private val lastDataTimeFlow = MutableStateFlow(0L)
    private val lastBolusTimeFlow = MutableStateFlow<Long?>(state.lastBolusTime)
    private val lastBolusAmountFlow = MutableStateFlow(state.lastBolusAmount?.let(::PumpInsulin))
    private val reservoirFlow = MutableStateFlow(PumpInsulin(state.reservoir))
    private var publishedPumpState = state.pumpState

    override val lastDataTime: StateFlow<Long> = lastDataTimeFlow
    override val lastBolusTime: StateFlow<Long?> = lastBolusTimeFlow
    override val lastBolusAmount: StateFlow<PumpInsulin?> = lastBolusAmountFlow
    override val baseBasalRate: PumpRate
        get() = PumpRate(state.getHourlyBasalFromMedtrumProfileArray(state.actualBasalProfile, clock.nowMillis()))
    override val reservoirLevel: StateFlow<PumpInsulin> = reservoirFlow

    // Android also has no battery value for this pump.
    override val batteryLevel: StateFlow<Int?> = MutableStateFlow(null)

    /** The session got data from the pump: a notification, an answer or a finished connect. */
    private fun onSessionData() {
        lastDataTimeFlow.value = clock.nowMillis()
        publishState()
    }

    /** Copy the values the loop reads from [state] to the flows. Save the patch state when it changed. */
    internal fun publishState() {
        lastBolusTimeFlow.value = state.lastBolusTime
        lastBolusAmountFlow.value = state.lastBolusAmount?.let(::PumpInsulin)
        reservoirFlow.value = PumpInsulin(state.reservoir)
        if (state.pumpState != publishedPumpState) {
            publishedPumpState = state.pumpState
            store?.save(state)
            onPumpStateChanged(state.pumpState)
        }
    }

    /**
     * Android `MedtrumService.handlePumpStateUpdate`, the part that writes history: an alarm state
     * adds the alarm and an announcement. Without a running patch, and after an error, a fake zero
     * temp basal is set so the loop knows that no insulin comes out.
     */
    private fun onPumpStateChanged(newState: MedtrumPumpState) {
        val alarm = history.announcePumpState(newState)
        if (alarm != null) state.addAlarm(alarm)
        val needsFakeTbr = when (newState) {
            MedtrumPumpState.NONE, MedtrumPumpState.STOPPED,
            MedtrumPumpState.IDLE, MedtrumPumpState.FILLED,
            MedtrumPumpState.PRIMING, MedtrumPumpState.PRIMED,
            MedtrumPumpState.EJECTING, MedtrumPumpState.EJECTED,
            MedtrumPumpState.OCCLUSION, MedtrumPumpState.EXPIRED,
            MedtrumPumpState.RESERVOIR_EMPTY, MedtrumPumpState.PATCH_FAULT,
            MedtrumPumpState.PATCH_FAULT2, MedtrumPumpState.BASE_FAULT,
            MedtrumPumpState.BATTERY_OUT, MedtrumPumpState.NO_CALIBRATION -> true
            else -> false
        }
        if (needsFakeTbr && history.setFakeTbrIfNotSet()) {
            state.lastBasalType = BasalType.NONE
            state.lastBasalRate = 0.0
            state.lastBasalDuration = MedtrumPumpSyncHistory.FAKE_TBR_LENGTH_MIN.toInt()
        }
    }

    override fun connect(reason: String) {
        // The session suspends inside, so run it on the owner scope. Callers use isReady().
        scope.launch {
            val connected = try {
                session.connect(reason)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("PUMPCOMM", "connect crashed ($reason): ${e.message}")
                false
            }
            if (!connected) logger.warn("PUMPCOMM", "connect failed ($reason)")
            publishState()
            store?.save(state)
        }
    }

    override fun disconnect(reason: String) {
        session.disconnect(reason)
    }

    // Android: drop the BLE attempt. The session also ends a connect that is still waiting.
    override fun stopConnecting() {
        session.stopConnecting()
    }

    override suspend fun getPumpStatus(reason: String) {
        if (!isReady()) return
        // Status arrives via notifications; the session already polls on its schedule.
        // A manual refresh is a no-op here — kept for interface conformance.
    }

    // -- Therapy ---------------------------------------------------------------

    override suspend fun deliverTreatment(detailedBolusInfo: DetailedBolusInfo): PumpEnactResult =
        commandMutex.withLock {
            if (!isReady()) return failClosed("Pump not ready — bolus refused")
            busy = true
            try {
                var bolusStart: Long? = null
                val result = session.setBolus(detailedBolusInfo.insulin) { start ->
                    bolusStart = start
                    history.bolusStarted(detailedBolusInfo, start)
                }
                // The amount comes from the pump, never from the request.
                val delivered = state.bolusAmountDelivered
                // Android: stopped before any insulin came out. The pump writes no record for this.
                val startedAt = bolusStart
                if (startedAt != null && state.bolusStopped && delivered == 0.0) {
                    history.bolusStoppedWithoutInsulin(detailedBolusInfo, startedAt)
                }
                val verdict = bolusVerdict(result, detailedBolusInfo.insulin, delivered, pumpDescription.bolusStep)
                if (verdict && delivered > 0.0) {
                    state.lastBolusAmount = delivered
                    state.lastBolusTime = state.bolusStartTime
                    publishState()
                }
                enactResult(
                    success = verdict,
                    enacted = delivered > 0.0,
                    comment = if (verdict) "Bolus $delivered U delivered"
                    else "Bolus failed: $result, asked ${detailedBolusInfo.insulin} U, delivered $delivered U",
                    bolusDelivered = delivered,
                )
            } finally {
                busy = false
                store?.save(state)
            }
        }

    // Not under commandMutex. deliverTreatment holds the mutex for the whole bolus, so a stop that
    // waited for it would run only after the bolus was finished. The session sends the cancel at
    // once, like Android. It only waits for a command that is in flight at that moment.
    override fun stopBolusDelivering() {
        if (!isInitialized()) return
        scope.launch {
            session.stopBolus()
            store?.save(state)
        }
    }

    override suspend fun setTempBasalAbsolute(
        absoluteRate: Double,
        durationInMinutes: Int,
        enforceNew: Boolean,
        tbrType: PumpSync.TemporaryBasalType,
    ): PumpEnactResult = commandMutex.withLock {
        if (!isReady()) return failClosed("Pump not ready — TBR refused")
        busy = true
        try {
            // Android stores the request first. The status from the pump is matched with it.
            history.rememberTempBasalRequest(absoluteRate, durationInMinutes, tbrType)
            if (session.setTempBasal(absoluteRate, durationInMinutes)) {
                enactResult(
                    success = true, enacted = true,
                    comment = "TBR $absoluteRate U/h for $durationInMinutes min",
                    duration = durationInMinutes, absolute = absoluteRate,
                )
            } else {
                enactResult(success = false, enacted = false, comment = "TBR failed")
            }
        } finally {
            busy = false
            store?.save(state)
        }
    }

    override suspend fun setTempBasalPercent(
        percent: Int,
        durationInMinutes: Int,
        enforceNew: Boolean,
        tbrType: PumpSync.TemporaryBasalType,
    ): PumpEnactResult =
        // Mirrors Android: Medtrum does not support percent TBR — fail closed, no approximation.
        enactResult(success = false, enacted = false, comment = "Percent TBR not supported on Medtrum")

    override suspend fun cancelTempBasal(enforceNew: Boolean): PumpEnactResult =
        commandMutex.withLock {
            if (!isReady()) return failClosed("Pump not ready — cancel TBR refused")
            busy = true
            try {
                if (session.cancelTempBasal()) {
                    enactResult(success = true, enacted = true, comment = "TBR cancelled", isTempCancel = true)
                } else {
                    enactResult(success = false, enacted = false, comment = "Cancel TBR failed")
                }
            } finally {
                busy = false
                store?.save(state)
            }
        }

    override suspend fun setNewBasalProfile(profile: PumpProfile): PumpEnactResult =
        commandMutex.withLock {
            if (!isReady()) return failClosed("Pump not ready — basal profile refused")
            val pairs = profile.getBasalValues().map { it.value to it.timeAsSeconds }
            busy = true
            try {
                if (session.updateBasalProfile(pairs)) {
                    enactResult(success = true, enacted = true, comment = "Basal profile updated")
                } else {
                    enactResult(success = false, enacted = false, comment = "Basal profile update failed")
                }
            } finally {
                busy = false
                store?.save(state)
            }
        }

    override fun isThisProfileSet(profile: PumpProfile): Boolean {
        if (!isReady()) return true
        val expected = state.buildMedtrumProfileArray(profile.getBasalValues().map { it.value to it.timeAsSeconds })
            ?: return false
        return expected.contentEquals(state.actualBasalProfile)
    }

    override suspend fun setExtendedBolus(insulin: Double, durationInMinutes: Int): PumpEnactResult =
        enactResult(success = false, enacted = false, comment = "Extended bolus not supported on Medtrum")

    override suspend fun cancelExtendedBolus(): PumpEnactResult =
        enactResult(success = false, enacted = false, comment = "Extended bolus not supported on Medtrum")

    // -- Plumbing ---------------------------------------------------------------

    private fun failClosed(comment: String): PumpEnactResult =
        enactResult(success = false, enacted = false, comment = comment)

    private fun enactResult(
        success: Boolean = false,
        enacted: Boolean = false,
        comment: String = "",
        duration: Int = 0,
        absolute: Double = 0.0,
        isTempCancel: Boolean = false,
        bolusDelivered: Double = 0.0,
    ): PumpEnactResult = IosPumpEnactResult(
        success = success, enacted = enacted, comment = comment, duration = duration,
        absolute = absolute, isTempCancel = isTempCancel, bolusDelivered = bolusDelivered,
        textResolver = textResolver,
    )

    override suspend fun loadTDDs(): PumpEnactResult =
        enactResult(success = false, enacted = false, comment = "TDD not yet wired")

    override fun canHandleDST(): Boolean = false

    override fun getCustomActions(): List<CustomAction>? = null
    override fun executeCustomAction(customActionType: CustomActionType) = Unit
    override fun executeCustomCommand(customCommand: CustomCommand): PumpEnactResult? = null
}

/**
 * The bolus verdict, same rule as the Android `MedtrumPlugin.deliverTreatment`: the pump must
 * report the asked amount (within one [bolusStep]), or the user stopped the bolus.
 *
 * [delivered] is the amount the pump tracked, not the amount that was asked for.
 */
internal fun bolusVerdict(
    result: MedtrumSession.BolusResult,
    requested: Double,
    delivered: Double,
    bolusStep: Double,
): Boolean = when (result) {
    MedtrumSession.BolusResult.STOPPED -> true
    // The link was fine when the command went out, so the amount decides (Android: connectionOK).
    MedtrumSession.BolusResult.DELIVERED,
    MedtrumSession.BolusResult.TIMEOUT -> abs(requested - delivered) < bolusStep
    MedtrumSession.BolusResult.NOT_CONNECTED,
    MedtrumSession.BolusResult.ALREADY_IN_PROGRESS,
    MedtrumSession.BolusResult.SEND_FAILED -> false
}
