package app.aaps.pump.medtrum.ios

import app.aaps.core.data.pump.defs.ManufacturerType
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.pump.PumpProfile
import app.aaps.core.interfaces.pump.PumpRate
import app.aaps.core.interfaces.pump.PumpInsulin
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.queue.CustomAction
import app.aaps.core.interfaces.queue.CustomActionType
import app.aaps.core.interfaces.queue.CustomCommand
import app.aaps.pump.medtrum.code.ConnectionState
import app.aaps.pump.medtrum.session.MedtrumClock
import app.aaps.pump.medtrum.session.MedtrumSession
import app.aaps.pump.medtrum.session.MedtrumSessionState
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val transport: IosMedtrumBleTransport,
    val state: MedtrumSessionState,
    private val clock: MedtrumClock,
    private val store: IosMedtrumStateStore? = null,
) : Pump {

    private val session = MedtrumSession(
        transport = transport,
        state = state,
        clock = clock,
    ).also { transport.setCallback(it) }

    private val commandMutex = Mutex()
    private var busy = false

    init {
        store?.load(state)
    }

    // -- Safety guard --------------------------------------------------------

    /** True only when the session completed auth AND the BLE link is up. */
    fun isReady(): Boolean =
        session.phase == MedtrumSession.Phase.READY &&
            state.connectionState == ConnectionState.CONNECTED

    // -- Pump identity -------------------------------------------------------

    override fun manufacturer(): ManufacturerType = ManufacturerType.Medtrum
    override fun model(): PumpType = PumpType.MEDTRUM_NANO
    override fun serialNumber(): String = state.pumpSN.toString()

    override val pumpDescription: PumpDescription = PumpDescription().apply {
        pumpType = PumpType.MEDTRUM_NANO
        isBolusCapable = true
        bolusStep = 0.05
        isTempBasalCapable = true
        isExtendedBolusCapable = false
    }

    override val isFakingTempsByExtendedBoluses: Boolean = false

    // -- State ----------------------------------------------------------------

    override fun isConfigured(): Boolean = state.pumpSN != 0L
    override fun isInitialized(): Boolean = isReady()
    override fun isSuspended(): Boolean = false
    override fun isBusy(): Boolean = busy
    override fun isConnected(): Boolean = state.connectionState == ConnectionState.CONNECTED
    override fun isConnecting(): Boolean = state.connectionState == ConnectionState.CONNECTING
    override fun isHandshakeInProgress(): Boolean = session.phase == MedtrumSession.Phase.CONNECTING

    override val lastDataTime: StateFlow<Long> = MutableStateFlow(0L)
    override val lastBolusTime: StateFlow<Long?> = MutableStateFlow(null)
    override val lastBolusAmount: StateFlow<PumpInsulin?> = MutableStateFlow(null)
    override val baseBasalRate: PumpRate = PumpRate(0.0)
    override val reservoirLevel: StateFlow<PumpInsulin> = MutableStateFlow(PumpInsulin(0.0))
    override val batteryLevel: StateFlow<Int?> = MutableStateFlow(null)

    override fun connect(reason: String) {
        // Fire-and-forget: the session suspends internally; callers use isReady().
        // A structured scope is the shell's job (M4); here we just kick the flow.
        GlobalScope.launch {
            session.connect(reason)
            store?.save(state)
        }
    }

    override fun disconnect(reason: String) {
        session.disconnect(reason)
    }

    override fun stopConnecting() = Unit

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
                when (val result = session.setBolus(detailedBolusInfo.insulin)) {
                    MedtrumSession.BolusResult.DELIVERED ->
                        IosPumpEnactResult(
                            success = true, enacted = true,
                            comment = "Bolus ${detailedBolusInfo.insulin} U delivered",
                            bolusDelivered = detailedBolusInfo.insulin,
                        )
                    MedtrumSession.BolusResult.STOPPED ->
                        IosPumpEnactResult(
                            success = true, enacted = true,
                            comment = "Bolus stopped",
                            bolusDelivered = state.bolusAmountDelivered,
                        )
                    else ->
                        IosPumpEnactResult(
                            success = false, enacted = false,
                            comment = "Bolus failed: $result",
                        )
                }
            } finally {
                busy = false
                store?.save(state)
            }
        }

    override fun stopBolusDelivering() {
        GlobalScope.launch {
            commandMutex.withLock {
                if (!isReady()) return@withLock
                session.stopBolus()
                store?.save(state)
            }
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
            if (session.setTempBasal(absoluteRate, durationInMinutes)) {
                IosPumpEnactResult(
                    success = true, enacted = true,
                    comment = "TBR $absoluteRate U/h for $durationInMinutes min",
                    duration = durationInMinutes, absolute = absoluteRate,
                )
            } else {
                IosPumpEnactResult(success = false, enacted = false, comment = "TBR failed")
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
        IosPumpEnactResult(success = false, enacted = false, comment = "Percent TBR not supported on Medtrum")

    override suspend fun cancelTempBasal(enforceNew: Boolean): PumpEnactResult =
        commandMutex.withLock {
            if (!isReady()) return failClosed("Pump not ready — cancel TBR refused")
            busy = true
            try {
                if (session.cancelTempBasal()) {
                    IosPumpEnactResult(success = true, enacted = true, comment = "TBR cancelled", isTempCancel = true)
                } else {
                    IosPumpEnactResult(success = false, enacted = false, comment = "Cancel TBR failed")
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
                    IosPumpEnactResult(success = true, enacted = true, comment = "Basal profile updated")
                } else {
                    IosPumpEnactResult(success = false, enacted = false, comment = "Basal profile update failed")
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
        IosPumpEnactResult(success = false, enacted = false, comment = "Extended bolus not supported on Medtrum")

    override suspend fun cancelExtendedBolus(): PumpEnactResult =
        IosPumpEnactResult(success = false, enacted = false, comment = "Extended bolus not supported on Medtrum")

    // -- Plumbing ---------------------------------------------------------------

    private fun failClosed(comment: String): PumpEnactResult =
        IosPumpEnactResult(success = false, enacted = false, comment = comment)

    override suspend fun loadTDDs(): PumpEnactResult =
        IosPumpEnactResult(success = false, enacted = false, comment = "TDD not yet wired")

    override fun canHandleDST(): Boolean = false

    override fun getCustomActions(): List<CustomAction>? = null
    override fun executeCustomAction(customActionType: CustomActionType) = Unit
    override fun executeCustomCommand(customCommand: CustomCommand): PumpEnactResult? = null
}
