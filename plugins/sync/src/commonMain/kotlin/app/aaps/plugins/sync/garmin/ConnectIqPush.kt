package app.aaps.plugins.sync.garmin

/**
 * One watch app a push should go to right now.
 *
 * [token] identifies this attempt. A completion that arrives after the watchdog has moved on carries
 * the old token and is ignored, so it cannot clear the attempt that replaced it.
 */
internal data class ConnectIqTarget(
    val deviceUuid: String,
    val appId: String,
    val token: Long = 0L,
)

/**
 * What [ConnectIqPush] wants the caller to do.
 *
 * [log] is set only when a send failed or the watchdog gave up on one, so a watch that is out of
 * range does not fill the log on every retry that is still waiting. [released] is how many attempts
 * the watchdog dropped; the caller ends that many background tasks. [stale] is a completion for an
 * attempt that is no longer the one in flight.
 */
internal data class ConnectIqPlan(
    val send: List<ConnectIqTarget>,
    val log: String?,
    val released: Int = 0,
    val stale: Boolean = false,
)

/**
 * The result names the iPhone bridge reports. The order matches `IQSendMessageResult` in Connect IQ
 * SDK 1.8.0, and the ordinal is the raw value the Swift bridge switches on. Android's messenger
 * retries a dropped transfer with its own timer; this is the iPhone side of that idea: a failure
 * waits, and the next glucose does not pile another message on top.
 */
internal enum class ConnectIqSendResult {
    SUCCESS,
    FAILURE_UNKNOWN,
    FAILURE_INTERNAL_ERROR,
    FAILURE_DEVICE_NOT_AVAILABLE,
    FAILURE_APP_NOT_FOUND,
    FAILURE_DEVICE_IS_BUSY,
    FAILURE_UNSUPPORTED_TYPE,
    FAILURE_INSUFFICIENT_MEMORY,
    FAILURE_TIMEOUT,
    FAILURE_MAX_RETRIES,
    FAILURE_PROMPT_NOT_DISPLAYED,
    FAILURE_APP_ALREADY_RUNNING,
    ;

    companion object {
        fun fromWire(result: String): ConnectIqSendResult =
            entries.firstOrNull { it.name == result } ?: FAILURE_UNKNOWN
    }
}

/**
 * Which pushes may leave the phone.
 *
 * At most one message is in flight per watch app. A newer glucose that arrives while that one is
 * still going out is remembered and sent once, when the in-flight one finishes, instead of being
 * queued behind it. A failure waits: two seconds, then four, up to a minute. An app the watch does
 * not have waits half an hour, and a payload the SDK refuses waits a day.
 *
 * If the SDK never calls back — the usual case once iOS has suspended the process — [reapStuck]
 * drops the attempt after [inFlightTimeoutMs] and logs one line. A value that arrived during the
 * wait is sent then, instead of the push staying dead until the next launch.
 *
 * The switch is [enabled]. When it is off, nothing is sent and a failure does not schedule another.
 */
internal class ConnectIqPush(
    private val nowMs: () -> Long,
    private val enabled: () -> Boolean,
    private val baseDelayMs: Long = 2_000L,
    private val maxDelayMs: Long = 60_000L,
    private val appNotFoundDelayMs: Long = 30L * 60L * 1000L,
    private val permanentDelayMs: Long = 24L * 60L * 60L * 1000L,
    private val inFlightTimeoutMs: Long = IN_FLIGHT_TIMEOUT_MS,
) {

    private class Slot {
        var inFlight: Boolean = false
        var dirty: Boolean = false
        var attempt: Int = 0
        var nextMs: Long = 0L
        var startedMs: Long = 0L
        var token: Long = 0L
    }

    private val slots = HashMap<Pair<String, String>, Slot>()
    private val lock = GarminLock()
    private var nextToken = 1L

    /**
     * @param allow false for an app we already know is not installed on that watch.
     */
    fun request(
        deviceUuids: List<String>,
        appIds: List<String>,
        allow: (deviceUuid: String, appId: String) -> Boolean = { _, _ -> true },
    ): ConnectIqPlan = lock.withLock {
        val reaped = reapStuckLocked()
        val already = reaped.send.map { it.deviceUuid to it.appId }.toSet()
        val fresh = requestLocked(deviceUuids, appIds, allow, already)
        ConnectIqPlan(
            send = reaped.send + fresh.send,
            log = reaped.log ?: fresh.log,
            released = reaped.released,
        )
    }

    /**
     * Drops attempts whose completion never came. Safe to call when nothing is stuck: the log is
     * null then, including the call after one that already logged.
     */
    fun reapStuck(): ConnectIqPlan = lock.withLock { reapStuckLocked() }

    /** The bridge finished one send. May ask for the same target again, with the latest payload. */
    fun onResult(
        deviceUuid: String,
        appId: String,
        result: ConnectIqSendResult,
        token: Long = 0L,
    ): ConnectIqPlan = lock.withLock { onResultLocked(deviceUuid, appId, result, token) }

    private fun requestLocked(
        deviceUuids: List<String>,
        appIds: List<String>,
        allow: (deviceUuid: String, appId: String) -> Boolean,
        alreadySending: Set<Pair<String, String>>,
    ): ConnectIqPlan {
        if (!enabled()) return ConnectIqPlan(emptyList(), null)
        val send = ArrayList<ConnectIqTarget>()
        for (device in deviceUuids) {
            for (app in appIds) {
                if (!allow(device, app)) continue
                val key = device to app
                if (key in alreadySending) continue
                val slot = slots.getOrPut(key) { Slot() }
                if (slot.inFlight) {
                    slot.dirty = true
                    continue
                }
                if (nowMs() < slot.nextMs) continue
                arm(slot)
                send.add(ConnectIqTarget(device, app, slot.token))
            }
        }
        return ConnectIqPlan(send, null)
    }

    private fun reapStuckLocked(): ConnectIqPlan {
        val now = nowMs()
        val send = ArrayList<ConnectIqTarget>()
        val notes = ArrayList<String>()
        var released = 0
        for ((key, slot) in slots) {
            if (!slot.inFlight) continue
            if (now - slot.startedMs < inFlightTimeoutMs) continue
            slot.inFlight = false
            released++
            notes += "Connect IQ watchdog reset device=${key.first} app=${key.second}"
            if (slot.dirty && enabled()) {
                arm(slot)
                send.add(ConnectIqTarget(key.first, key.second, slot.token))
            }
        }
        return ConnectIqPlan(send, notes.takeIf { it.isNotEmpty() }?.joinToString("; "), released = released)
    }

    private fun onResultLocked(
        deviceUuid: String,
        appId: String,
        result: ConnectIqSendResult,
        token: Long,
    ): ConnectIqPlan {
        val slot = slots.getOrPut(deviceUuid to appId) { Slot() }
        if (token != 0L && (slot.token != token || !slot.inFlight)) {
            return ConnectIqPlan(emptyList(), null, stale = true)
        }
        slot.inFlight = false
        val log = if (result == ConnectIqSendResult.SUCCESS) {
            slot.attempt = 0
            slot.nextMs = 0L
            null
        } else {
            slot.attempt += 1
            val delay = delayMs(slot.attempt, result)
            slot.nextMs = nowMs() + delay
            "Connect IQ $result device=$deviceUuid app=$appId attempt=${slot.attempt} retryInMs=$delay"
        }
        if (!enabled()) {
            slot.dirty = false
            return ConnectIqPlan(emptyList(), log)
        }
        if (slot.dirty && nowMs() >= slot.nextMs) {
            arm(slot)
            return ConnectIqPlan(listOf(ConnectIqTarget(deviceUuid, appId, slot.token)), log)
        }
        return ConnectIqPlan(emptyList(), log)
    }

    private fun arm(slot: Slot) {
        slot.token = nextToken++
        slot.inFlight = true
        slot.dirty = false
        slot.startedMs = nowMs()
    }

    private fun delayMs(attempt: Int, result: ConnectIqSendResult): Long = when (result) {
        ConnectIqSendResult.FAILURE_APP_NOT_FOUND -> appNotFoundDelayMs
        ConnectIqSendResult.FAILURE_UNSUPPORTED_TYPE,
        ConnectIqSendResult.FAILURE_INSUFFICIENT_MEMORY -> permanentDelayMs
        else -> {
            val shift = (attempt - 1).coerceIn(0, 5)
            (baseDelayMs shl shift).coerceAtMost(maxDelayMs)
        }
    }

    companion object {
        /** Long enough for a transfer, short enough that the next glucose is not stuck behind it. */
        const val IN_FLIGHT_TIMEOUT_MS = 20_000L
    }
}
