package app.aaps.plugins.source.dexcomoneplus.session

import android.content.Context
import android.os.PowerManager
import android.util.Log
import app.aaps.plugins.source.dexcomoneplus.OnePlusGlucoseSample
import app.aaps.plugins.source.dexcomoneplus.OnePlusLogMarkers
import app.aaps.plugins.source.dexcomoneplus.OnePlusWarmupState
import app.aaps.plugins.source.dexcomoneplus.gatt.OnePlusGattClient
import app.aaps.plugins.source.dexcomoneplus.gatt.OnePlusGattClientUnimplemented
import app.aaps.plugins.source.dexcomoneplus.oem.DeviceProfileRegistry
import app.aaps.plugins.source.dexcomoneplus.oem.OemDeviceProfile
import app.aaps.plugins.source.dexcomoneplus.reconnect.OemAwareReconnectPolicy
import app.aaps.plugins.source.dexcomoneplus.reconnect.OnePlusReconnectPolicy
import app.aaps.plugins.source.dexcomoneplus.warmup.OnePlusWarmupClock

/**
 * ONE+ BLE session lifecycle (scan already done → connect → auth → Control EGV → warm-up/glucose).
 *
 * ⚠️ ASYNC IMPACT: Real fills run on bleExecutor; [startWithPairingCode] blocks that thread
 * through KEKS + [OnePlusEgvSession] loop + reconnect sleeps. [stop] must be callable without
 * waiting for the executor queue (sets [running] false + GATT disconnect to unblock awaits).
 */
interface OnePlusBleSession {
    fun startWithPairingCode(deviceAddress: String, pairingCode: String)
    fun stop(reason: String?)
    fun isUp(): Boolean
    fun warmupState(): OnePlusWarmupState
}

/**
 * Outcome of the pre-connect step (`beforeConnect`).
 *
 * @param advFresh a recent ADV sighting for the target MAC is in hand (UI handoff or in-window
 *   rescan hit) → prefer a fast direct connect (`autoConnect=false`) over the OEM autoConnect park.
 * @param blindFallback the persistent ADV wait gave up waiting and authorises **one** connect
 *   without a sighting. Without this escape a restored session can never leave the wait, because
 *   [OnePlusBleSessionCyclePolicy.allowConnection] refuses every ADV-less connect (field log
 *   2026-08-03: 18 min of `target ADV absent — remain waiting`, no glucose, no error, no retry).
 * @param foreignAdv redacted label of the strongest *other* ONE+ heard while the target stayed
 *   silent — the stale-stored-MAC signature.
 */
data class OnePlusConnectPrep(
    val advFresh: Boolean = false,
    val blindFallback: Boolean = false,
    val foreignAdv: String? = null,
)

/**
 * Pure decisions for the post-Control-loop transition, kept separate from Android BLE calls so the
 * normal duty-cycle behavior can be unit tested.
 */
internal object OnePlusBleSessionCyclePolicy {
    const val POST_COLLECTION_RECONNECT_ATTEMPT = 1

    /**
     * Continuous ADV silence after which the persistent wait authorises one blind connect. Long
     * enough that a normal G7 duty cycle (~5 min) never triggers it, short enough that a sensor the
     * phone simply stopped hearing is retried rather than waited on forever.
     */
    const val BLIND_CONNECT_AFTER_ADV_SILENCE_MS = 4L * 60_000L

    /** Continuous ADV silence after which a foreign ONE+ sighting is reported as a stale-MAC suspicion. */
    const val STALE_MAC_SUSPICION_AFTER_MS = 6L * 60_000L

    /**
     * A cycle that proved the link works ends in the persistent ADV wait instead of the bounded
     * failure budget.
     *
     * Gating this on a *usable glucose value* made warm-up impossible to survive: a warming sensor
     * only sends `WarmingUp` packets, so every normal duty-cycle disconnect burned one retry, and the
     * budget was exhausted (FAILED, session dead) minutes into the ~30 min warm-up. Any parsed
     * Control packet proves the same thing a glucose value does — the sensor is ours, authenticated
     * and talking.
     */
    fun waitForAdvertisementAfterExit(sessionProvedHealthy: Boolean): Boolean =
        sessionProvedHealthy

    /**
     * Control traffic that proves the session is alive. [OnePlusWarmupState.Phase.FAILED] is
     * excluded on purpose: a stopped / expired / failed sensor must keep the bounded budget so it
     * ends in a real failure instead of waiting for an advertisement that means nothing.
     */
    fun controlTrafficProvesSession(phase: OnePlusWarmupState.Phase): Boolean =
        phase == OnePlusWarmupState.Phase.WARMING || phase == OnePlusWarmupState.Phase.READY

    /**
     * Retry budget exhausted: recover into the persistent ADV wait instead of the terminal FAILED,
     * but ONLY when the Control channel has already delivered at least once. Such a sensor is
     * asleep between radio windows, not lost, so waiting is right.
     *
     * The proof must be real Control traffic, never a successful authentication. A sensor that has
     * no session started completes the whole handshake and then hangs up on the first glucose
     * request, so "authenticated" is true on every cycle while the slot receives nothing at all.
     * Recovering on that signal removed the terminal FAILED, and with it the only way back to a
     * fresh sensor start (SessionStart is issued on attempt 0 only) — the slot then retried for
     * ever and could never come back.
     */
    fun recoverExhaustedBudgetWithPersistentWait(sessionEverProvedControlChannel: Boolean): Boolean =
        sessionEverProvedControlChannel

    /**
     * Warm-up deadline to remember once [state] has been emitted, so the countdown survives the
     * CONNECTING / RECONNECTING states of a duty cycle (those carry no clock of their own and used
     * to blank the UI countdown until the next EGV packet).
     */
    fun warmupDeadlineAfter(previousEndsAtMs: Long?, state: OnePlusWarmupState): Long? =
        when (state.phase) {
            OnePlusWarmupState.Phase.WARMING      -> state.endsAtEpochMs ?: previousEndsAtMs
            OnePlusWarmupState.Phase.READY,
            OnePlusWarmupState.Phase.IDLE,
            OnePlusWarmupState.Phase.FAILED       -> null
            OnePlusWarmupState.Phase.PAIRING,
            OnePlusWarmupState.Phase.CONNECTING,
            OnePlusWarmupState.Phase.RECONNECTING -> previousEndsAtMs
        }

    fun applyFailureBudget(preparedPostCollectionAdvertisement: Boolean): Boolean =
        !preparedPostCollectionAdvertisement

    fun requireFreshAdvertisementBeforeReconnect(persistentAdvertisementMode: Boolean): Boolean =
        persistentAdvertisementMode

    fun allowConnection(
        restoredSessionMode: Boolean,
        advertisementFresh: Boolean,
        blindFallbackAuthorized: Boolean = false,
    ): Boolean = !restoredSessionMode || advertisementFresh || blindFallbackAuthorized

    /** The persistent wait has been silent long enough to try a connect without a sighting. */
    fun authorizeBlindConnect(continuousAdvSilenceMs: Long): Boolean =
        continuousAdvSilenceMs >= BLIND_CONNECT_AFTER_ADV_SILENCE_MS

    /** Another ONE+ is advertising while ours never does → the stored MAC is probably not ours. */
    fun suspectStaleMac(continuousAdvSilenceMs: Long, foreignSightings: Int): Boolean =
        foreignSightings > 0 && continuousAdvSilenceMs >= STALE_MAC_SUSPICION_AFTER_MS

    /** Whole minutes of silence — the granularity at which the wait re-emits a warm-up state. */
    fun advSilenceMinutes(continuousAdvSilenceMs: Long): Long =
        continuousAdvSilenceMs.coerceAtLeast(0L) / 60_000L
}

/**
 * Session: pairing validation → GATT connect → KEKS auth → Control/EGV loop, with OEM
 * reconnect retries after GATT drop / connect-auth failure. After a successful EGV cycle, the
 * transmitter's disconnect is treated as normal duty cycling: wait persistently for its next ADV
 * instead of consuming the finite failure budget while the radio is asleep.
 *
 * Does **not** claim production BLE. Stub driver remains the default façade.
 */
class OnePlusBleSessionSkeleton(
    private val gatt: OnePlusGattClient = OnePlusGattClientUnimplemented(),
    private val auth: OnePlusSessionAuth = OnePlusSessionAuthUnimplemented(),
    private val reconnectPolicy: OnePlusReconnectPolicy = OemAwareReconnectPolicy(),
    private val profile: OemDeviceProfile = DeviceProfileRegistry.resolve(),
    private val onWarmup: (OnePlusWarmupState) -> Unit = {},
    private val onGlucose: (OnePlusGlucoseSample) -> Unit = {},
    private val onSession: (Boolean, String?) -> Unit = { _, _ -> },
    private val onError: (String, Boolean) -> Unit = { _, _ -> },
    /**
     * When true on the **first** successful connect only: SessionStart **if** the
     * transmitter has no session yet. Never auto SessionStop (Dexcom recovery).
     * Reconnect attempts always pass false.
     */
    private val requestNewSensorStart: Boolean = true,
    /**
     * Ob1-style pre-connect: LE rescan / handoff before [OnePlusGattClient.connect].
     * Runs on bleExecutor; must not touch UI. Returns [OnePlusConnectPrep] (ADV freshness).
     * Default no-op (tests / stub).
     */
    private val beforeConnect: (deviceAddress: String, attempt: Int) -> OnePlusConnectPrep =
        { _, _ -> OnePlusConnectPrep() },
    /** Juggluco-style reconnect: preload 16-byte KEKS shared key when available. */
    private val savedSharedKeyProvider: () -> ByteArray? = { null },
    /** Persist MAC + shared key after successful auth. */
    private val onAuthSuccess: (deviceAddress: String, sharedKey: ByteArray) -> Unit = { _, _ -> },
    /** Clear persisted shared key when short-auth / bond path is invalidated. */
    private val onAuthInvalidate: () -> Unit = {},
    /** Optional app context for PARTIAL_WAKE_LOCK during connect/auth (Juggluco). */
    private val appContext: Context? = null,
    /** Sensor slot owning this session (`prod` / `staging`) — logged on every marker. */
    private val slot: String = OnePlusLogMarkers.SLOT_PRODUCTION,
) : OnePlusBleSession {

    @Volatile
    private var up = false

    @Volatile
    private var running = false

    @Volatile
    private var cancelled = false

    private val lifecycleLock = Any()

    @Volatile
    private var warmup = OnePlusWarmupState(phase = OnePlusWarmupState.Phase.IDLE)

    /**
     * Last known end of warm-up, kept across connection phases so the UI countdown does not blank
     * out on every duty cycle. Maintained by [OnePlusBleSessionCyclePolicy.warmupDeadlineAfter].
     */
    @Volatile
    private var warmupEndsAtEpochMs: Long? = null

    /**
     * At least one cycle received real Control traffic (warm-up state or glucose) — the sensor is
     * ours AND its data channel works.
     *
     * This must NOT be "auth succeeded": a sensor with no active session completes the whole KEKS
     * handshake and then drops the link as soon as it is asked for glucose (peer status 19). Using
     * auth as the proof kept such a sensor in the persistent wait for ever, so the retry budget
     * never ran out, the session never reached FAILED, and the only path that re-issues
     * SessionStart (a fresh start on attempt 0) could never be reached again.
     */
    @Volatile
    private var everProvedControlChannel = false

    override fun startWithPairingCode(deviceAddress: String, pairingCode: String) {
        val codeError = OnePlusSessionStart.validationError(pairingCode)
        if (codeError != null) {
            fail(codeError, fatal = false)
            return
        }
        val normalized = OnePlusSessionStart.normalizePairingCode(pairingCode)
        if (deviceAddress.isBlank()) {
            Log.i(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.SESSION}: [$slot] start pairing (addr blank) profile=${profile.id}",
            )
        } else {
            Log.i(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.SESSION}: [$slot] start pairing addr=${redactAddress(deviceAddress)} profile=${profile.id}",
            )
        }

        val startAllowed = synchronized(lifecycleLock) {
            if (cancelled) {
                false
            } else {
                running = true
                true
            }
        }
        if (!startAllowed) {
            Log.i(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.SESSION}: [$slot] start ignored — session already stopped",
            )
            return
        }
        setWarmup(OnePlusWarmupState(phase = OnePlusWarmupState.Phase.PAIRING, message = "pairing"))

        var attempt = 0
        var preparedConnect: OnePlusConnectPrep? = null
        // A restored session must never fall back to a blind connect while the transmitter sleeps.
        // Explicit new-sensor setup may use its existing bounded initial-connect policy.
        var persistentAdvertisementMode = !requestNewSensorStart
        while (running) {
            if (attempt > 0) {
                val applyFailureBudget = OnePlusBleSessionCyclePolicy.applyFailureBudget(
                    preparedPostCollectionAdvertisement = preparedConnect != null,
                )
                if (applyFailureBudget && !reconnectPolicy.shouldRetry(attempt, profile)) {
                    if (!OnePlusBleSessionCyclePolicy.recoverExhaustedBudgetWithPersistentWait(everProvedControlChannel)) {
                        fail("ONEPLUS_RECONNECT_EXHAUSTED: attempts=$attempt", fatal = false)
                        return
                    }
                    // A sensor that has already sent us Control traffic is asleep, not lost: keep
                    // waiting for its next radio window instead of ending in a terminal FAILED only
                    // a manual restart could leave.
                    Log.w(
                        OnePlusLogMarkers.TAG,
                        "${OnePlusLogMarkers.RECONNECT}: [$slot] retry budget exhausted after proven Control " +
                            "traffic (attempts=$attempt) — switch to persistent ADV wait",
                    )
                    persistentAdvertisementMode = true
                    preparedConnect = preparePostCollectionReconnect(
                        deviceAddress = deviceAddress,
                        reason = "retry_budget_exhausted_waiting_for_adv",
                    )
                    if (!running || preparedConnect == null) return
                    attempt = OnePlusBleSessionCyclePolicy.POST_COLLECTION_RECONNECT_ATTEMPT
                    // The prepared fresh ADV bypasses the backoff delay and the pre-connect rescan.
                    continue
                }
                if (applyFailureBudget) {
                    val delayMs = reconnectPolicy.nextDelayMs(attempt, profile)
                    Log.i(
                        OnePlusLogMarkers.TAG,
                        "${OnePlusLogMarkers.RECONNECT}: [$slot] attempt=$attempt delayMs=$delayMs " +
                            "profile=${profile.id} aggressive=${profile.aggressiveReconnect}",
                    )
                    if (!sleepWhileRunning(delayMs)) {
                        stop("cancelled")
                        return
                    }
                } else {
                    Log.i(
                        OnePlusLogMarkers.TAG,
                        "${OnePlusLogMarkers.RECONNECT}: [$slot] fresh ADV ready — bypass retry delay " +
                            "attempt=$attempt profile=${profile.id}",
                    )
                }
            } else {
                Log.i(
                    OnePlusLogMarkers.TAG,
                    "${OnePlusLogMarkers.RECONNECT}: [$slot] attempt=0 " +
                        "maxRetries=${profile.connectRetryCount} timeoutMs=${profile.connectTimeoutMs}",
                )
            }

            val wantSessionStart = OnePlusSessionStartPolicy.wantSessionStartOnAttempt(
                requestNewSensorStart = requestNewSensorStart,
                attempt = attempt,
            )
            val outcome = runConnectionCycle(
                deviceAddress = deviceAddress,
                pairingCode = normalized,
                requestNewSensorStart = wantSessionStart,
                attempt = attempt,
                preparedConnect = preparedConnect,
            )
            preparedConnect = null
            if (!running) return

            when (outcome) {
                CycleOutcome.Stopped -> return
                CycleOutcome.HealthyCycleThenExited -> {
                    persistentAdvertisementMode = true
                    preparedConnect = preparePostCollectionReconnect(
                        deviceAddress = deviceAddress,
                        reason = "egv_cycle_complete_waiting_for_adv",
                    )
                    if (!running || preparedConnect == null) return
                    // Attempt 1 suppresses SessionStart and reports a reconnect, while the prepared
                    // fresh ADV bypasses both the backoff delay and a second pre-connect scan.
                    attempt = OnePlusBleSessionCyclePolicy.POST_COLLECTION_RECONNECT_ATTEMPT
                }
                CycleOutcome.EgvExited -> {
                    if (OnePlusBleSessionCyclePolicy.requireFreshAdvertisementBeforeReconnect(
                            persistentAdvertisementMode,
                        )
                    ) {
                        preparedConnect = preparePostCollectionReconnect(
                            deviceAddress = deviceAddress,
                            reason = "egv_loop_exit_waiting_for_adv",
                        )
                        if (!running || preparedConnect == null) return
                        attempt = OnePlusBleSessionCyclePolicy.POST_COLLECTION_RECONNECT_ATTEMPT
                    } else {
                        // Drop before the first usable EGV: retain bounded failure retries.
                        up = false
                        try {
                            gatt.disconnect()
                        } catch (_: Throwable) {
                        }
                        onSession(false, "egv_loop_exit")
                        enterReconnecting("egv_loop_exit")
                        Log.i(
                            OnePlusLogMarkers.TAG,
                            "${OnePlusLogMarkers.SESSION}: [$slot] down reason=egv_loop_exit → reconnect",
                        )
                        attempt++
                    }
                }
                CycleOutcome.RetryableFailure -> {
                    if (OnePlusBleSessionCyclePolicy.requireFreshAdvertisementBeforeReconnect(
                            persistentAdvertisementMode,
                        )
                    ) {
                        preparedConnect = preparePostCollectionReconnect(
                            deviceAddress = deviceAddress,
                            reason = "cycle_failure_waiting_for_adv",
                        )
                        if (!running || preparedConnect == null) return
                        attempt = OnePlusBleSessionCyclePolicy.POST_COLLECTION_RECONNECT_ATTEMPT
                    } else {
                        up = false
                        try {
                            gatt.disconnect()
                        } catch (_: Throwable) {
                        }
                        attempt++
                    }
                }
                CycleOutcome.Fatal -> return
            }
        }
    }

    /**
     * Safe to call from any thread. Clears [running] and disconnects GATT so Control awaits unblock.
     *
     * ⚠️ ASYNC IMPACT: Must not be queued behind [startWithPairingCode] on the same single-thread
     * executor alone — callers should invoke [stop] directly (see [OnePlusCgmDriverReal.disconnect]).
     */
    override fun stop(reason: String?) {
        synchronized(lifecycleLock) {
            cancelled = true
            running = false
            up = false
            warmup = OnePlusWarmupState(phase = OnePlusWarmupState.Phase.IDLE, message = reason)
            warmupEndsAtEpochMs = null
        }
        gatt.disconnect()
        emitWarmup()
        onSession(false, reason)
        Log.i(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.SESSION}: [$slot] down reason=$reason")
    }

    override fun isUp(): Boolean = up

    override fun warmupState(): OnePlusWarmupState = warmup

    private fun runConnectionCycle(
        deviceAddress: String,
        pairingCode: String,
        requestNewSensorStart: Boolean,
        attempt: Int,
        preparedConnect: OnePlusConnectPrep?,
    ): CycleOutcome {
        // CONNECTING (first attempt) / RECONNECTING (retry) — never PAIRING here, so the UI can tell
        // "establishing link" from the terminal FAILED it used to flash between retries.
        val connectingPhase =
            if (attempt == 0) OnePlusWarmupState.Phase.CONNECTING else OnePlusWarmupState.Phase.RECONNECTING
        setWarmup(OnePlusWarmupState(phase = connectingPhase, message = "connect_attempt_$attempt"))

        val prep = preparedConnect ?: try {
            beforeConnect(deviceAddress, attempt)
        } catch (t: Throwable) {
            val msg = t.message ?: "ONEPLUS_BEFORE_CONNECT_FAILED"
            Log.e(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.ERROR}: [$slot] beforeConnect failed: $msg attempt=$attempt",
            )
            onError(msg, false)
            enterReconnecting(msg)
            return if (running) CycleOutcome.RetryableFailure else CycleOutcome.Stopped
        }
        if (!running) return CycleOutcome.Stopped
        if (!OnePlusBleSessionCyclePolicy.allowConnection(
                restoredSessionMode = !this.requestNewSensorStart,
                advertisementFresh = prep.advFresh,
                blindFallbackAuthorized = prep.blindFallback,
            )
        ) {
            Log.i(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.SCAN}: [$slot] restored session requires fresh ADV — remain waiting",
            )
            enterReconnecting("waiting_for_next_adv")
            return CycleOutcome.RetryableFailure
        }
        if (prep.blindFallback) {
            Log.w(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.SESSION}: [$slot] blind connect — no ADV sighting, " +
                    "persistent wait escape (attempt=$attempt)",
            )
        }

        // Fresh ADV in hand → fast direct connect (fail-fast status 133 → quick retry). Otherwise fall
        // back to the OEM autoConnect policy (Samsung parks a background connect when the sensor is
        // quiet — that park cost ~48 s in the field log; the handoff avoids it when a sighting exists).
        val useAutoConnect = when {
            prep.advFresh -> false
            profile.autoConnectFromAttempt < 0 -> false
            else -> attempt >= profile.autoConnectFromAttempt
        }
        Log.i(
            OnePlusLogMarkers.TAG,
            "${OnePlusLogMarkers.SESSION}: [$slot] connect decision advFresh=${prep.advFresh} " +
                "autoConnect=$useAutoConnect attempt=$attempt profile=${profile.id}",
        )
        try {
            gatt.connect(deviceAddress, autoConnect = useAutoConnect)
        } catch (t: Throwable) {
            val msg = t.message ?: "ONEPLUS_GATT_FAILED"
            Log.e(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.ERROR}: [$slot] $msg attempt=$attempt", t)
            onError(msg, false)
            enterReconnecting(msg)
            return if (running) CycleOutcome.RetryableFailure else CycleOutcome.Stopped
        }

        if (!running) return CycleOutcome.Stopped

        val wakeLock = acquireAuthWakeLock()
        try {
            val authResult = auth.authenticate(pairingCode, savedSharedKeyProvider())
            if (!running) return CycleOutcome.Stopped
            if (!authResult.ok) {
                val msg = authResult.message ?: "ONEPLUS_AUTH_FAILED"
                Log.e(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.ERROR}: [$slot] $msg attempt=$attempt")
                if (authResult.invalidateSharedKey ||
                    msg.contains("bond failure", ignoreCase = true) ||
                    msg.contains("key refresh", ignoreCase = true) ||
                    msg.contains("Missing QR", ignoreCase = true) ||
                    msg.contains("short-auth rejected", ignoreCase = true)
                ) {
                    // Stale shared key / cert path — force full KEKS next attempt (Juggluco resetCerts).
                    try {
                        onAuthInvalidate()
                    } catch (_: Throwable) {
                    }
                }
                onError(msg, false)
                // Retryable: the loop will re-attempt (short-auth reject wipes the key → full J-PAKE
                // next). Show RECONNECTING, NOT FAILED — the old FAILED flash made the user abandon
                // during the reconnect delay (field log 23:18:40).
                enterReconnecting(msg)
                return if (running) CycleOutcome.RetryableFailure else CycleOutcome.Stopped
            }

            authResult.sharedKey?.let { key ->
                try {
                    onAuthSuccess(deviceAddress, key)
                } catch (t: Throwable) {
                    Log.w(
                        OnePlusLogMarkers.TAG,
                        "${OnePlusLogMarkers.SESSION}: [$slot] onAuthSuccess persist failed: ${t.message}",
                    )
                }
            }

            // Do NOT invent a 30 min WARMING clock here — that blocked ingest even when the
            // transmitter was already producing Ok EGVs. Protocol (TransmitterTime / EGV) sets phase.
            up = true
            setWarmup(OnePlusWarmupState(phase = OnePlusWarmupState.Phase.PAIRING, message = "auth_ok"))
            onSession(true, if (attempt == 0) "session_up" else "session_reconnected")
            Log.i(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.SESSION}: [$slot] up attempt=$attempt newStart=$requestNewSensorStart — Control/EGV",
            )

            // Any Control traffic proves the link — a warming sensor never sends usable glucose, so
            // gating the persistent ADV wait on glucose alone made warm-up burn the retry budget.
            var sessionProvedHealthy = false
            val egv = OnePlusEgvSession(
                gatt = gatt,
                onWarmup = { state ->
                    if (OnePlusBleSessionCyclePolicy.controlTrafficProvesSession(state.phase)) {
                        sessionProvedHealthy = true
                        everProvedControlChannel = true
                    }
                    setWarmup(state)
                },
                onGlucose = { sample ->
                    sessionProvedHealthy = true
                    everProvedControlChannel = true
                    onGlucose(sample)
                },
                onError = onError,
                requestNewSensorStart = requestNewSensorStart,
            )
            try {
                egv.run(shouldContinue = { running && gatt.isConnected() })
            } catch (t: Throwable) {
                if (!running) return CycleOutcome.Stopped
                val msg = t.message ?: "ONEPLUS_CONTROL_FAILED"
                Log.e(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.ERROR}: [$slot] $msg", t)
                onError(msg, false)
                enterReconnecting(msg)
                return CycleOutcome.RetryableFailure
            }

            return when {
                !running -> CycleOutcome.Stopped
                OnePlusBleSessionCyclePolicy.waitForAdvertisementAfterExit(sessionProvedHealthy) ->
                    CycleOutcome.HealthyCycleThenExited
                else -> CycleOutcome.EgvExited
            }
        } finally {
            // Juggluco holds wake for the whole connection; we keep it through first EGV cycle.
            releaseWakeLock(wakeLock)
        }
    }

    private fun acquireAuthWakeLock(): PowerManager.WakeLock? {
        val ctx = appContext ?: return null
        return try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AndroidAPS::DexcomOnePlusAuth").apply {
                setReferenceCounted(false)
                acquire(AUTH_WAKE_LOCK_MS)
            }
        } catch (t: Throwable) {
            Log.w(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.SESSION}: [$slot] wakeLock ${t.message}")
            null
        }
    }

    private fun releaseWakeLock(lock: PowerManager.WakeLock?) {
        if (lock == null) return
        try {
            if (lock.isHeld) lock.release()
        } catch (_: Throwable) {
        }
    }

    /**
     * Transient / retryable transition. UI shows RECONNECTING (never terminal FAILED) so the user
     * keeps waiting through the reconnect delay. Terminal failure stays in [fail].
     */
    private fun enterReconnecting(
        message: String?,
        advSilenceMinutes: Long? = null,
        staleMacSuspected: Boolean = false,
    ) {
        if (!running) return
        setWarmup(
            OnePlusWarmupState(
                phase = OnePlusWarmupState.Phase.RECONNECTING,
                message = message,
                advSilenceMinutes = advSilenceMinutes,
                staleMacSuspected = staleMacSuspected,
            ),
        )
    }

    /**
     * Ends the current successful duty cycle and waits for the next transmitter radio window.
     * Watcher exceptions are isolated so they cannot terminate the persistent BLE loop.
     */
    private fun preparePostCollectionReconnect(
        deviceAddress: String,
        reason: String,
    ): OnePlusConnectPrep? {
        if (!running) return null
        up = false
        try {
            gatt.disconnect()
        } catch (_: Throwable) {
        }
        if (!running) return null
        try {
            onSession(false, reason)
        } catch (t: Throwable) {
            Log.w(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.SESSION}: [$slot] down callback failed: ${t.message}",
            )
        }
        if (!running) return null
        try {
            enterReconnecting("waiting_for_next_adv")
        } catch (t: Throwable) {
            Log.w(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.WARMUP}: [$slot] waiting callback failed: ${t.message}",
            )
        }
        if (!running) return null
        Log.i(
            OnePlusLogMarkers.TAG,
            "${OnePlusLogMarkers.SESSION}: [$slot] cycle down reason=$reason — wait persistently for next ADV",
        )
        return awaitFreshAdvertisement(deviceAddress)
    }

    /**
     * A successful EGV cycle resets failure semantics. Dexcom advertises only in short windows, so
     * repeatedly scan until the target MAC is actually visible and hand that fresh sighting directly
     * to the next connection cycle — no finite retry exhaustion while the radio is merely asleep.
     *
     * The wait is **not** unbounded any more:
     * - every whole minute of silence re-emits a RECONNECTING state, so the UI / ongoing
     *   notification shows progress instead of a frozen warm-up;
     * - after [OnePlusBleSessionCyclePolicy.BLIND_CONNECT_AFTER_ADV_SILENCE_MS] it returns a
     *   `blindFallback` prep so the cycle may connect without a sighting (a sensor whose ADV the
     *   phone can no longer hear is otherwise never retried);
     * - if another ONE+ is heard while ours never is, the stale-MAC suspicion is surfaced through
     *   [onError] once per wait.
     *
     * ⚠️ ASYNC IMPACT: blocks bleExecutor in bounded scan calls; [stop] clears [running], so this
     * loop exits as soon as the current bounded pre-connect scan returns.
     */
    private fun awaitFreshAdvertisement(deviceAddress: String): OnePlusConnectPrep? {
        val silenceStartedMs = System.currentTimeMillis()
        var lastEmittedMinute = -1L
        var lastEmittedStale = false
        var staleMacReported = false
        var foreignEverSeen: String? = null
        while (running) {
            val prep = try {
                beforeConnect(
                    deviceAddress,
                    OnePlusBleSessionCyclePolicy.POST_COLLECTION_RECONNECT_ATTEMPT,
                )
            } catch (t: Throwable) {
                Log.w(
                    OnePlusLogMarkers.TAG,
                    "${OnePlusLogMarkers.SCAN}: [$slot] waiting for next ADV failed: ${t.message}",
                )
                OnePlusConnectPrep(advFresh = false)
            }
            if (!running) return null
            if (prep.advFresh) {
                Log.i(
                    OnePlusLogMarkers.TAG,
                    "${OnePlusLogMarkers.SCAN}: [$slot] next-cycle target ADV acquired",
                )
                return prep
            }

            val silenceMs = System.currentTimeMillis() - silenceStartedMs
            val silenceMinutes = OnePlusBleSessionCyclePolicy.advSilenceMinutes(silenceMs)
            // A transmitter only advertises in short windows, so a foreign sighting is sporadic:
            // remember it for the whole wait instead of requiring it in the same 8 s scan as the
            // suspicion threshold.
            prep.foreignAdv?.let { foreignEverSeen = it }

            if (!staleMacReported &&
                OnePlusBleSessionCyclePolicy.suspectStaleMac(silenceMs, if (foreignEverSeen != null) 1 else 0)
            ) {
                staleMacReported = true
                val message = "ONEPLUS_ADV_STALE_MAC: target ${redactAddress(deviceAddress)} silent " +
                    "${silenceMinutes}min while another ONE+ advertises ($foreignEverSeen) — " +
                    "sensor replaced or started in the other slot?"
                Log.e(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.ERROR}: [$slot] $message")
                try {
                    onError(message, false)
                } catch (_: Throwable) {
                }
            }

            if (OnePlusBleSessionCyclePolicy.authorizeBlindConnect(silenceMs)) {
                Log.w(
                    OnePlusLogMarkers.TAG,
                    "${OnePlusLogMarkers.SCAN}: [$slot] ADV silent ${silenceMinutes}min — " +
                        "authorising one blind connect (escape from persistent wait)",
                )
                return prep.copy(blindFallback = true)
            }

            // One state per whole minute (and immediately when the stale-MAC suspicion appears):
            // enough for the UI / ongoing notification to show progress, rare enough not to churn
            // the notification or the warm-up basal guard every 8 s.
            if (silenceMinutes != lastEmittedMinute || staleMacReported != lastEmittedStale) {
                lastEmittedMinute = silenceMinutes
                lastEmittedStale = staleMacReported
                enterReconnecting(
                    message = "waiting_for_adv",
                    advSilenceMinutes = silenceMinutes,
                    staleMacSuspected = staleMacReported,
                )
                if (!running) return null
            }
            Log.i(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.SCAN}: [$slot] target ADV absent — remain waiting " +
                    "silentMs=$silenceMs foreign=${foreignEverSeen ?: "-"}",
            )
            if (!sleepWhileRunning(ADV_WAIT_RESTART_DELAY_MS)) return null
        }
        return null
    }

    private fun fail(message: String, fatal: Boolean) {
        running = false
        up = false
        try {
            gatt.disconnect()
        } catch (_: Throwable) {
        }
        setWarmup(OnePlusWarmupState(phase = OnePlusWarmupState.Phase.FAILED, message = message))
        onError(message, fatal)
        onSession(false, message)
        Log.e(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.ERROR}: [$slot] $message fatal=$fatal")
        Log.i(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.SESSION}: [$slot] down reason=fail")
    }

    /**
     * Publishes [state] and keeps the warm-up deadline sticky: connection phases carry no clock of
     * their own, so without this the UI countdown (and the ongoing notification) went blank on every
     * duty cycle and only came back with the next EGV packet.
     */
    private fun setWarmup(state: OnePlusWarmupState) {
        val published = synchronized(lifecycleLock) {
            // A stopped / superseded session must not be resurrected by a late callback still queued
            // on the BLE executor: it would republish a stale phase and, worse, bring the sticky
            // deadline back for every later state. [stop] owns the final IDLE state.
            if (cancelled && state.phase != OnePlusWarmupState.Phase.IDLE) return
            val endsAt = OnePlusBleSessionCyclePolicy.warmupDeadlineAfter(warmupEndsAtEpochMs, state)
            warmupEndsAtEpochMs = endsAt
            warmup = if (state.endsAtEpochMs == null) state.copy(endsAtEpochMs = endsAt) else state
            warmup
        }
        emitWarmup(published)
    }

    /** Logs and publishes [state] to the watcher — outside [lifecycleLock], callbacks are foreign code. */
    private fun emitWarmup(state: OnePlusWarmupState = warmup) {
        val remaining = OnePlusWarmupClock.resolveRemainingMs(state, System.currentTimeMillis())
        Log.i(
            OnePlusLogMarkers.TAG,
            "${OnePlusLogMarkers.WARMUP}: [$slot] phase=${state.phase} remainingMs=$remaining " +
                "msg=${state.message} advSilenceMin=${state.advSilenceMinutes ?: "-"} " +
                "staleMac=${state.staleMacSuspected}",
        )
        onWarmup(state)
    }

    /** Interruptible sleep that exits early when [stop] clears [running]. */
    private fun sleepWhileRunning(delayMs: Long): Boolean {
        if (delayMs <= 0L) return running
        val end = System.currentTimeMillis() + delayMs
        while (running && System.currentTimeMillis() < end) {
            val slice = (end - System.currentTimeMillis()).coerceAtMost(200L).coerceAtLeast(1L)
            try {
                Thread.sleep(slice)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return running
    }

    private fun redactAddress(address: String): String {
        if (address.length <= 5) return "***"
        return "***" + address.takeLast(5)
    }

    private enum class CycleOutcome {
        /** The cycle proved the session works (glucose or warm-up Control traffic) before exiting. */
        HealthyCycleThenExited,
        EgvExited,
        RetryableFailure,
        Stopped,
        Fatal,
    }

    companion object {
        /**
         * Cover KEKS + bond prompt + first Control/EGV cycle.
         * Juggluco uses an unbounded wake lock for the whole connection; we bound it.
         */
        private const val AUTH_WAKE_LOCK_MS = 600_000L
        private const val ADV_WAIT_RESTART_DELAY_MS = 250L
    }
}
