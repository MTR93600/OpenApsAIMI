package app.aaps.plugins.sync.garmin

/**
 * One watch app a push should go to right now.
 */
internal data class ConnectIqTarget(val deviceUuid: String, val appId: String)

/**
 * What [ConnectIqPush] wants the caller to do. [log] is set only when a send failed, one line, so a
 * watch that is out of range does not fill the log on every retry that is still waiting.
 */
internal data class ConnectIqPlan(val send: List<ConnectIqTarget>, val log: String?)

/**
 * The result names the iPhone bridge reports. The order matches `IQSendMessageResult` in Connect IQ
 * SDK 1.8.0. Android's messenger retries a dropped transfer with its own timer; this is the iPhone
 * side of that idea: a failure waits, and the next glucose does not pile another message on top.
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
 * not have waits half an hour, and a payload the SDK refuses waits a day, so a missing widget does
 * not get a message every five minutes.
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
) {

    private class Slot {
        var inFlight: Boolean = false
        var dirty: Boolean = false
        var attempt: Int = 0
        var nextMs: Long = 0L
    }

    private val slots = HashMap<Pair<String, String>, Slot>()
    private val lock = GarminLock()

    /**
     * @param allow false for an app we already know is not installed on that watch.
     */
    fun request(
        deviceUuids: List<String>,
        appIds: List<String>,
        allow: (deviceUuid: String, appId: String) -> Boolean = { _, _ -> true },
    ): ConnectIqPlan = lock.withLock {
        if (!enabled()) return ConnectIqPlan(emptyList(), null)
        val send = ArrayList<ConnectIqTarget>()
        for (device in deviceUuids) {
            for (app in appIds) {
                if (!allow(device, app)) continue
                val slot = slots.getOrPut(device to app) { Slot() }
                if (slot.inFlight) {
                    slot.dirty = true
                    continue
                }
                if (nowMs() < slot.nextMs) continue
                slot.inFlight = true
                slot.dirty = false
                send.add(ConnectIqTarget(device, app))
            }
        }
        return ConnectIqPlan(send, null)
    }

    /** The bridge finished one send. May ask for the same target again, with the latest payload. */
    fun onResult(deviceUuid: String, appId: String, result: ConnectIqSendResult): ConnectIqPlan = lock.withLock {
        val slot = slots.getOrPut(deviceUuid to appId) { Slot() }
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
            slot.dirty = false
            slot.inFlight = true
            return ConnectIqPlan(listOf(ConnectIqTarget(deviceUuid, appId)), log)
        }
        return ConnectIqPlan(emptyList(), log)
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
}
