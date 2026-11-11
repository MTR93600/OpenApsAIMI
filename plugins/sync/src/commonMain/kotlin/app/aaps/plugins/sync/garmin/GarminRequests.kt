package app.aaps.plugins.sync.garmin

import androidx.annotation.VisibleForTesting
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.sync.SyncStrings
import app.aaps.plugins.sync.garmin.keys.GarminIntNonKey
import app.aaps.plugins.sync.garmin.keys.GarminLongNonKey
import app.aaps.plugins.sync.garmin.keys.GarminStringKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The query of a watch request, `a=1&b=2`, read the way the Android plugin always read it: split on `&`
 * and `=`, no URL decoding, the first `name=value` pair wins.
 */
class GarminQuery(val raw: String?) {

    operator fun get(name: String): String? = (raw ?: "")
        .split("&")
        .map { kv -> kv.split("=") }
        .firstOrNull { kv -> kv.size == 2 && kv[0] == name }?.get(1)

    fun contains(text: String): Boolean = (raw ?: "").contains(text, ignoreCase = true)
}

/**
 * What AAPS answers to the Garmin watch apps, on every phone.
 *
 * The watch asks over HTTP (`/get`, `/sgv.json`, `/carbs`, `/bolus`, `/temptarget`, `/mode`,
 * `/connect`) and sends heart rate and steps on the same requests. This class holds every rule for
 * those answers; the platform only runs the server and passes the query in. Android and the iPhone
 * must answer the same text, so the watch apps work unchanged on both. `GarminPluginTest` checks
 * the exact JSON.
 *
 * ⚠️ ASYNC IMPACT: called on the HTTP server threads. [getGlucoseValues] with a wait blocks up to that
 * wait for the next glucose value.
 */
class GarminRequests(
    private val aapsLogger: AAPSLogger,
    private val rh: TextResolver,
    private val preferences: Preferences,
    private val loopHub: LoopHub,
    private val persistenceLayer: PersistenceLayer,
    private val config: Config,
    private val notificationManager: NotificationManager,
) {

    /** The current time in ms. A test sets its own. */
    @VisibleForTesting
    var nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() }

    /** The time zone that decides where a day starts, for the steps of the day. A test sets its own. */
    @VisibleForTesting
    var timeZone: () -> TimeZone = { TimeZone.currentSystemDefault() }

    /** Time of the newest glucose value seen, to wake a request that waits for it. */
    private val newestGlucose = MutableStateFlow<Long?>(null)

    @VisibleForTesting
    val newestGlucoseTimestamp: Long? get() = newestGlucose.value

    /**
     * Waits at most [maxWaitMs] for a newer glucose value than the one seen when the wait started.
     * A test sets its own, to see the wait without waiting.
     */
    @VisibleForTesting
    var awaitNewGlucose: (maxWaitMs: Long) -> Unit = { maxWaitMs ->
        val seen = newestGlucose.value
        runBlocking { withTimeoutOrNull(maxWaitMs) { newestGlucose.first { it != seen } } }
    }

    /** When the last watch bolus was sent on, see [BOLUS_REPEAT_BLOCK_MS]. */
    @Volatile
    private var lastWatchBolusMs: Long? = null

    private val glucoseUnitStr get() = if (loopHub.glucoseUnit == GlucoseUnit.MGDL) "mgdl" else "mmoll"
    private val garminAapsKey get() = preferences.get(GarminStringKey.RequestKey)

    // -- requests --------------------------------------------------------------------------------

    /** True when no request key is set, or when the watch sent the right one (`key=`). */
    fun keyAccepted(query: GarminQuery): Boolean {
        val key = garminAapsKey
        return key.isEmpty() || key == query["key"]
    }

    /**
     * The answer to a request on [path], or null when the path is not one the watch apps use. The same
     * paths the Android server registers in `GarminPlugin.setupHttpServer`.
     */
    fun answer(path: String, query: GarminQuery): String? = when (path) {
        "/get"        -> onGetBloodGlucose(query)
        "/carbs"      -> onPostCarbs(query)
        "/bolus"      -> onPostBolus(query)
        "/temptarget" -> onPostTempTarget(query)
        "/mode"       -> onPostMode(query)
        "/connect"    -> onConnectPump(query)
        "/sgv.json"   -> onSgv(query)
        else          -> null
    }

    // -- glucose ---------------------------------------------------------------------------------

    /** New glucose values were stored: remembers the newest and wakes a request waiting for it. */
    fun onNewBloodGlucose(glucoseValues: List<GV>) {
        val timestamp = glucoseValues.maxOfOrNull { it.timestamp } ?: return
        aapsLogger.info(LTag.GARMIN, "onNewBloodGlucose ${Instant.fromEpochMilliseconds(timestamp)}")
        if ((newestGlucose.value ?: 0) >= timestamp) return
        newestGlucose.value = timestamp
    }

    /** Gets the last 2+ hours of glucose values. */
    fun getGlucoseValues(): List<GV> {
        val from = Instant.fromEpochMilliseconds(nowMs() - GLUCOSE_HISTORY_MS)
        return loopHub.getGlucoseValues(from, true)
    }

    /** Gets the last 2+ hours of glucose values, and waits in case a new value should arrive soon. */
    fun getGlucoseValues(maxWaitMs: Long): List<GV> {
        val glucoseValues = getGlucoseValues()
        val last = glucoseValues.lastOrNull() ?: return emptyList()
        val delayMs = nowMs() - last.timestamp
        return if (maxWaitMs != 0L && delayMs > GLUCOSE_FREQUENCY_MS && delayMs < GLUCOSE_FREQUENCY_MS + 60_000L) {
            aapsLogger.debug(LTag.GARMIN, "waiting for new glucose (delay=${delayMs}ms)")
            awaitNewGlucose(maxWaitMs)
            getGlucoseValues()
        } else {
            glucoseValues
        }
    }

    fun encodedGlucose(glucoseValues: List<GV>): String {
        val encoded = DeltaVarEncodedList(glucoseValues.size * 16, 2)
        for (glucose: GV in glucoseValues) {
            val timeSec: Int = (glucose.timestamp / 1000).toInt()
            val glucoseMgDl: Int = glucose.value.roundToInt()
            encoded.add(timeSec, glucoseMgDl)
        }
        return encoded.encodedBase64()
    }

    /** The message the phone pushes to the watch apps: the key and the glucose of the last 2 hours. */
    fun getGlucoseMessage(): Map<String, Any> = mapOf(
        "key" to garminAapsKey,
        "command" to "glucose",
        "profile" to loopHub.currentProfileName.first().toString(),
        "encodedGlucose" to encodedGlucose(getGlucoseValues()),
        "remainingInsulin" to loopHub.insulinOnboard,
        "remainingBasalInsulin" to loopHub.insulinBasalOnboard,
        "glucoseUnit" to glucoseUnitStr,
        "temporaryBasalRate" to (loopHub.temporaryBasal.takeIf { it.isFinite() } ?: 1.0),
        "connected" to loopHub.isConnected,
        "timestamp" to nowMs() / 1000
    )

    /**
     * Answers the get glucose request of the watch (`/get`).
     *
     * Also reads the heart rate and steps readings the watch sent with it.
     */
    fun onGetBloodGlucose(query: GarminQuery): String {
        receiveHeartRate(query)
        val profileName = loopHub.currentProfileName
        val waitSec = queryLong(query, "wait", 0L)
        val glucoseValues = getGlucoseValues(waitSec * 1000L)
        return buildJsonObject {
            put("encodedGlucose", encodedGlucose(glucoseValues))
            put("remainingInsulin", loopHub.insulinOnboard)
            put("remainingBasalInsulin", loopHub.insulinBasalOnboard)
            loopHub.lowGlucoseMark.takeIf { it > 0.0 }?.let { put("lowGlucoseMark", it.roundToInt()) }
            loopHub.highGlucoseMark.takeIf { it > 0.0 }?.let { put("highGlucoseMark", it.roundToInt()) }
            put("glucoseUnit", glucoseUnitStr)
            loopHub.temporaryBasal.also { if (!it.isNaN()) put("temporaryBasalRate", it) }
            put("profile", profileName.first().toString())
            put("connected", loopHub.isConnected)
        }.toString()
    }

    // -- query values ----------------------------------------------------------------------------

    private fun queryBoolean(query: GarminQuery, name: String, defaultValue: Boolean): Boolean =
        when (query[name]?.lowercase()) {
            "true"  -> true
            "false" -> false
            else    -> defaultValue
        }

    private fun queryLong(query: GarminQuery, name: String, defaultValue: Long): Long {
        val value = query[name]
        return if (value.isNullOrEmpty()) defaultValue
        else value.toLongOrNull() ?: defaultValue.also { aapsLogger.error(LTag.GARMIN, "invalid $name value '$value'") }
    }

    private fun queryInt(query: GarminQuery, name: String, defaultValue: Int): Int {
        val value = query[name]
        return if (value.isNullOrEmpty()) defaultValue
        else value.toIntOrNull() ?: defaultValue.also { aapsLogger.error(LTag.GARMIN, "invalid $name value '$value'") }
    }

    private fun queryDouble(query: GarminQuery, name: String, defaultValue: Double): Double {
        val value = query[name]
        return if (value.isNullOrEmpty()) defaultValue
        else value.toDoubleOrNull() ?: defaultValue.also { aapsLogger.error(LTag.GARMIN, "invalid $name value '$value'") }
    }

    /** Watch apps send numbers either as numbers or as text, so both are read. */
    private fun toLong(v: Any?): Long = when (v) {
        is Number -> v.toLong()
        is String -> v.toLongOrNull() ?: 0L
        else      -> 0L
    }

    private fun toInt(v: Any?): Int? = when (v) {
        is Number -> v.toInt()
        is String -> v.toDoubleOrNull()?.toInt()
        else      -> null
    }

    // -- heart rate and steps --------------------------------------------------------------------

    fun receiveHeartRate(msg: Map<String, Any>, test: Boolean) {
        val avg: Int = msg["hr"] as Int? ?: 0
        val samplingStartSec: Long = toLong(msg["hrStart"])
        val samplingEndSec: Long = toLong(msg["hrEnd"])
        val device: String? = msg["device"] as String?
        receiveHeartRate(
            Instant.fromEpochSeconds(samplingStartSec), Instant.fromEpochSeconds(samplingEndSec),
            avg, device, test
        )
        receiveSteps(msg, test)
    }

    fun receiveHeartRate(query: GarminQuery) {
        val avg: Int = queryLong(query, "hr", 0L).toInt()
        val samplingStartSec: Long = queryLong(query, "hrStart", 0L)
        val samplingEndSec: Long = queryLong(query, "hrEnd", 0L)
        val device: String? = query["device"]
        receiveHeartRate(
            Instant.fromEpochSeconds(samplingStartSec), Instant.fromEpochSeconds(samplingEndSec),
            avg, device, queryBoolean(query, "test", false)
        )
        receiveSteps(query)
    }

    private fun receiveHeartRate(samplingStart: Instant, samplingEnd: Instant, avg: Int, device: String?, test: Boolean) {
        aapsLogger.info(LTag.GARMIN, "average heart rate $avg BPM $samplingStart to $samplingEnd")
        if (test) return
        if (avg > 10 && samplingStart > Instant.fromEpochMilliseconds(0L) && samplingEnd > samplingStart) {
            loopHub.storeHeartRate(samplingStart, samplingEnd, avg, device)
        } else if (avg > 0) {
            aapsLogger.warn(LTag.GARMIN, "Skip saving invalid HR $avg $samplingStart..$samplingEnd")
        }
    }

    fun receiveSteps(msg: Map<String, Any>, test: Boolean) {
        aapsLogger.debug(LTag.GARMIN, "receiveSteps() keys: ${msg.keys.joinToString(", ")}")
        var samplingStartSec = toLong(msg["stepsStart"])
        var samplingEndSec = toLong(msg["stepsEnd"])
        // Some watch apps write the keys in lower case.
        if (samplingStartSec == 0L) samplingStartSec = toLong(msg["stepsstart"])
        if (samplingEndSec == 0L) samplingEndSec = toLong(msg["stepsend"])
        if (samplingStartSec == 0L || samplingEndSec == 0L) {
            // Steps without a time: use the last 5 minutes.
            if (msg.keys.any { it.contains("steps", ignoreCase = true) && it !in STEP_TIME_KEYS }) {
                val now = nowMs() / 1000
                aapsLogger.warn(LTag.GARMIN, "steps without a time, using the last 5 minutes. Keys: ${msg.keys.joinToString(",")}")
                samplingStartSec = now - 300
                samplingEndSec = now
            } else {
                return
            }
        }
        // A missing bucket counts as 0, so a watch may send only some of them.
        val steps5 = toInt(msg["steps5"]) ?: 0
        val steps10 = toInt(msg["steps10"]) ?: 0
        val steps15 = toInt(msg["steps15"]) ?: 0
        val steps30 = toInt(msg["steps30"]) ?: 0
        val steps60 = toInt(msg["steps60"]) ?: 0
        val steps180 = toInt(msg["steps180"]) ?: 0
        val device: String? = msg["device"] as String?
        if (steps5 <= 0 && steps10 <= 0 && steps15 <= 0 && steps30 <= 0 && steps60 <= 0 && steps180 <= 0) {
            aapsLogger.debug(LTag.GARMIN, "steps: all buckets are 0, not stored")
            return
        }
        receiveSteps(
            Instant.fromEpochSeconds(samplingStartSec), Instant.fromEpochSeconds(samplingEndSec),
            steps5, steps10, steps15, steps30, steps60, steps180, device, test,
        )
    }

    fun receiveSteps(query: GarminQuery) {
        aapsLogger.debug(LTag.GARMIN, "receiveSteps(HTTP) query: ${query.raw ?: "<empty>"}")
        var samplingStart: Long? = query["stepsStart"]?.toLongOrNull()
        var samplingEnd: Long? = query["stepsEnd"]?.toLongOrNull()
        if (samplingStart == null || samplingEnd == null) {
            if (query.contains("steps")) {
                val now = nowMs() / 1000
                aapsLogger.warn(LTag.GARMIN, "HTTP steps without a time, using the last 5 minutes")
                samplingStart = now - 300
                samplingEnd = now
            } else {
                return
            }
        }
        val steps5 = query["steps5"]?.toIntOrNull() ?: 0
        val steps10 = query["steps10"]?.toIntOrNull() ?: 0
        val steps15 = query["steps15"]?.toIntOrNull() ?: 0
        val steps30 = query["steps30"]?.toIntOrNull() ?: 0
        val steps60 = query["steps60"]?.toIntOrNull() ?: 0
        val steps180 = query["steps180"]?.toIntOrNull() ?: 0
        val device = query["device"]
        val test = queryBoolean(query, "test", false)
        if (steps5 <= 0 && steps10 <= 0 && steps15 <= 0 && steps30 <= 0 && steps60 <= 0 && steps180 <= 0) {
            // Some watch faces (for example Swissalpine) send only the day total as "steps=".
            val totalSteps = query["steps"]?.toIntOrNull() ?: -1
            if (totalSteps >= 0) {
                ingestHttpTotalSteps(device, totalSteps, samplingStart, samplingEnd)
            } else {
                aapsLogger.debug(LTag.GARMIN, "HTTP steps: all buckets are 0, not stored")
            }
            return
        }
        receiveSteps(
            Instant.fromEpochSeconds(samplingStart), Instant.fromEpochSeconds(samplingEnd),
            steps5, steps10, steps15, steps30, steps60, steps180, device, test,
        )
    }

    /**
     * Turns a day total of steps into the steps since the last total. The first total ever is only
     * kept as the starting point. A total that went down means a new day on the watch: it is stored as
     * it is, because it already holds only today's steps.
     */
    private fun ingestHttpTotalSteps(device: String?, totalSteps: Int, samplingStart: Long, samplingEnd: Long) {
        val now = nowMs()
        val lastTotal = preferences.get(GarminIntNonKey.LastTotalSteps)
        if (lastTotal < 0) {
            storeLastTotalSteps(totalSteps, now)
            aapsLogger.info(LTag.GARMIN, "HTTP steps: first total $totalSteps kept as the starting point")
            return
        }
        val delta = totalSteps - lastTotal
        val start = Instant.fromEpochSeconds(samplingStart)
        val end = Instant.fromEpochSeconds(samplingEnd)
        // No upper limit: after a long run without a sync the delta can be well above 3000 steps.
        if (delta > 0) {
            aapsLogger.info(LTag.GARMIN, "HTTP steps: delta $delta ($start to $end), total $totalSteps")
            storeLastTotalSteps(totalSteps, now)
            loopHub.storeStepsCount(start, end, delta, 0, 0, 0, 0, 0, device)
            return
        }
        aapsLogger.warn(LTag.GARMIN, "HTTP steps: delta $delta is not positive (total $totalSteps, last $lastTotal)")
        if (totalSteps > 0 && delta == 0) {
            storeLastTotalSteps(totalSteps, now)
            val zone = timeZone()
            val midnight = Instant.fromEpochMilliseconds(now).toLocalDateTime(zone).date.atStartOfDayIn(zone).toEpochMilliseconds()
            val todayCount = runBlocking {
                // The device name differs ("Garmin", "Garmin Connect", ...), so match the start.
                persistenceLayer.getStepsCountFromTimeToTime(midnight, now).count { it.device.startsWith("Garmin") }
            }
            if (todayCount == 0) {
                aapsLogger.info(LTag.GARMIN, "HTTP steps: nothing stored today yet, storing the total $totalSteps")
                loopHub.storeStepsCount(start, end, totalSteps, 0, 0, 0, 0, 0, device)
            } else {
                aapsLogger.info(LTag.GARMIN, "HTTP steps: delta 0 and $todayCount records today, not stored")
            }
        } else {
            // The total went down (or is 0): a new day started on the watch.
            aapsLogger.warn(LTag.GARMIN, "HTTP steps: new day, total $totalSteps taken as it is")
            storeLastTotalSteps(totalSteps, now)
            loopHub.storeStepsCount(start, end, totalSteps, 0, 0, 0, 0, 0, device)
        }
    }

    private fun storeLastTotalSteps(totalSteps: Int, now: Long) {
        preferences.put(GarminIntNonKey.LastTotalSteps, totalSteps)
        preferences.put(GarminLongNonKey.LastTotalStepsTime, now)
    }

    private fun receiveSteps(
        samplingStart: Instant,
        samplingEnd: Instant,
        steps5: Int,
        steps10: Int,
        steps15: Int,
        steps30: Int,
        steps60: Int,
        steps180: Int,
        device: String?,
        test: Boolean,
    ) {
        if (steps5 < 0 || steps10 < 0 || steps15 < 0 || steps30 < 0 || steps60 < 0 || steps180 < 0) {
            aapsLogger.warn(LTag.GARMIN, "Skip saving invalid steps values $steps5/$steps10/$steps15/$steps30/$steps60/$steps180")
            return
        }
        aapsLogger.info(LTag.GARMIN, "steps $steps5/$steps10/$steps15/$steps30/$steps60/$steps180 from $samplingStart to $samplingEnd")
        if (test) return
        if (samplingEnd > samplingStart) {
            loopHub.storeStepsCount(samplingStart, samplingEnd, steps5, steps10, steps15, steps30, steps60, steps180, device)
        } else {
            aapsLogger.warn(LTag.GARMIN, "Skip saving invalid steps period $samplingStart..$samplingEnd")
        }
    }

    // -- commands from the watch -----------------------------------------------------------------

    /** Carbs entered on the watch (`/carbs`). */
    fun onPostCarbs(query: GarminQuery): String {
        val carbs = queryLong(query, "carbs", 0L).toInt()
        if (carbs > 0) {
            if (config.AAPSCLIENT)
                refuseOnClient(rh.gs(SyncStrings.garmin_carbs_not_on_client, rh.gs(InterfacesStrings.format_carbs, carbs)))
            else
                loopHub.postCarbs(carbs)
        }
        return ""
    }

    /**
     * A bolus asked for by the watch (`bolus` in U).
     *
     * The local HTTP server has no other protection than the request key, and any app on the phone can
     * reach it. So a bolus is refused unless a request key is set in the Garmin settings, and it is
     * always refused on a client, where it would only be written down and never reach the pump. The
     * amount is limited by the max bolus, like every other bolus. The watch cannot show why a bolus was
     * refused or limited, so the phone says it with a notification.
     */
    fun onPostBolus(query: GarminQuery): String {
        val bolus = queryDouble(query, "bolus", 0.0)
        if (!bolus.isFinite() || bolus <= 0.0) return BOLUS_REFUSED_JSON
        val asked = rh.gs(InterfacesStrings.format_insulin_units, bolus)
        if (config.AAPSCLIENT) {
            refuseBolus(rh.gs(SyncStrings.garmin_bolus_not_on_client, asked))
            return BOLUS_REFUSED_JSON
        }
        if (garminAapsKey.isEmpty()) {
            refuseBolus(rh.gs(SyncStrings.garmin_bolus_needs_key, asked))
            return BOLUS_REFUSED_JSON
        }
        // A watch that retries, or a repeated request, must not give the bolus twice. Each bolus is
        // limited on its own, so without this nothing limits the total.
        val now = nowMs()
        val last = lastWatchBolusMs
        if (last != null && now - last in 0 until BOLUS_REPEAT_BLOCK_MS) {
            refuseBolus(rh.gs(SyncStrings.garmin_bolus_too_soon, asked))
            return BOLUS_REFUSED_JSON
        }
        val given = loopHub.postBolus(bolus) { reason ->
            refuseBolus(rh.gs(SyncStrings.garmin_bolus_not_delivered, asked, reason))
        }
        if (given <= 0.0) {
            refuseBolus(rh.gs(SyncStrings.garmin_bolus_refused_by_limits, asked))
            return BOLUS_REFUSED_JSON
        }
        lastWatchBolusMs = now
        if (given < bolus) {
            refuseBolus(rh.gs(SyncStrings.garmin_bolus_limited, asked, rh.gs(InterfacesStrings.format_insulin_units, given)))
        }
        return "{\"ok\":true,\"bolus\":$given}"
    }

    private fun refuseBolus(text: String) {
        aapsLogger.warn(LTag.GARMIN, text)
        notificationManager.post(id = NotificationId.GARMIN_BOLUS, text = text)
    }

    /**
     * Stores or cancels a temporary target from the watch: `target` in the profile unit, `duration` in
     * minutes. A target or duration of 0 cancels the current one.
     */
    fun onPostTempTarget(query: GarminQuery): String {
        val target = queryDouble(query, "target", 0.0)
        val duration = queryInt(query, "duration", 0)
        loopHub.postTempTarget(target, duration)
        return ""
    }

    /**
     * Activates an AIMI mode from the watch.
     * NOTE text = keyword only; duration query sets TherapyEvent.duration.
     * FCL also starts a temporary target 80 mg/dL for 30 minutes.
     * Query: /mode?mode=lunch&duration=60&key=...
     */
    fun onPostMode(query: GarminQuery): String {
        return when (val decision = GarminTherapyMode.decide(query["mode"], query["duration"])) {
            is GarminTherapyMode.Rejected -> {
                aapsLogger.warn(LTag.GARMIN, "Rejected therapy mode '${decision.rawMode}'")
                decision.json
            }

            is GarminTherapyMode.Accepted -> {
                if (decision.durationInvalid) {
                    aapsLogger.error(LTag.GARMIN, "invalid duration value '${decision.durationRaw}'")
                }
                loopHub.postTherapyMode(decision.mode, decision.reportedDurationMin)
                decision.tempTarget?.let { target ->
                    loopHub.postTempTarget(target.mgdl, target.durationMin)
                }
                decision.json
            }
        }
    }

    /** Pump connected or disconnected, as the user entered it on the watch (`/connect`). */
    fun onConnectPump(query: GarminQuery): String {
        val minutes = queryLong(query, "disconnectMinutes", 0L).toInt()
        if (config.AAPSCLIENT) {
            refuseOnClient(rh.gs(SyncStrings.garmin_pump_not_on_client))
        } else if (minutes > 0) {
            loopHub.disconnectPump(minutes)
        } else {
            loopHub.connectPump()
        }
        return buildJsonObject { put("connected", loopHub.isConnected) }.toString()
    }

    /**
     * A client only shows values on the watch. Carbs or a pump disconnect stored here would stay on the client and
     * never reach the master, so they are not used. The watch cannot show why, so the phone says it.
     */
    private fun refuseOnClient(text: String) {
        aapsLogger.warn(LTag.GARMIN, "not used on a client: $text")
        notificationManager.post(id = NotificationId.GARMIN_NOT_ON_CLIENT, text = text)
    }

    // -- Nightscout style glucose ----------------------------------------------------------------

    private fun glucoseSlopeMgDlPerMilli(glucose1: GV, glucose2: GV): Double =
        (glucose2.value - glucose1.value) / (glucose2.timestamp - glucose1.timestamp)

    /** Returns glucose values in Nightscout/Xdrip format (`/sgv.json`). */
    fun onSgv(query: GarminQuery): String {
        receiveHeartRate(query)
        val count = queryLong(query, "count", 24L).toInt().coerceAtMost(1000).coerceAtLeast(1)
        val briefMode = queryBoolean(query, "brief_mode", false)

        // Guess a start time to get [count+1] readings. This is a heuristic that only works if we get readings
        // every 5 minutes and we're not missing readings. We truncate in case we get more readings but we'll
        // get less, e.g., in case we're missing readings for the last half hour. We get one extra reading,
        // to compute the glucose delta.
        val from = Instant.fromEpochMilliseconds(nowMs() - 5L * 60_000L * (count + 1))
        val glucoseValues = loopHub.getGlucoseValues(from, false)
        return buildJsonArray {
            for (i in 0 until count.coerceAtMost(glucoseValues.size)) {
                add(sgvEntry(glucoseValues, i, briefMode))
            }
        }.toString()
    }

    private fun sgvEntry(glucoseValues: List<GV>, i: Int, briefMode: Boolean): JsonObject = buildJsonObject {
        val glucose = glucoseValues[i]
        if (!briefMode) {
            put("_id", glucose.id.toString())
            put("device", glucose.sourceSensor.toString())
            val timestamp = Instant.fromEpochMilliseconds(glucose.timestamp)
            put("deviceString", timestamp.toString())
            put("sysTime", timestamp.toString())
            glucose.raw?.let { raw -> put("unfiltered", raw) }
        }
        put("date", glucose.timestamp)
        put("sgv", glucose.value.roundToInt())
        if (i + 1 < glucoseValues.size) {
            // The 5 minute delta in mg/dL, rounded to a whole number so that a change of 1 is
            // still there after the watch's `toNumber()` or `format("%.0f")`.
            val delta = 300_000.0 * glucoseSlopeMgDlPerMilli(glucoseValues[i + 1], glucose)
            if (delta.isFinite()) put("delta", delta.roundToInt())
        }
        put("direction", glucose.trendArrow.text)
        glucose.noise?.let { n -> put("noise", n) }
        if (i == 0) {
            when (loopHub.glucoseUnit) {
                GlucoseUnit.MGDL -> put("units_hint", "mgdl")
                GlucoseUnit.MMOL -> put("units_hint", "mmol")
            }
            put("iob", loopHub.insulinOnboard + loopHub.insulinBasalOnboard)
            // Nightscout style "U/h/%", so the watch can show the rate and the percent.
            // temporaryBasal is a factor (1.0 = 100 %). NaN: no property, the watch keeps its last value.
            loopHub.temporaryBasal.also { tbrFactor ->
                if (!tbrFactor.isNaN()) {
                    val safeFactor = if (tbrFactor.isFinite()) tbrFactor else 0.0
                    val profileBasal = loopHub.currentProfile?.getBasal() ?: 0.0
                    val rateUph = profileBasal * safeFactor
                    val percent = (safeFactor * 100.0).toInt()
                    if (rateUph != 0.0 || percent != 0) {
                        put("tbr", "${formatOneDecimal(rateUph)}/$percent")
                    } else {
                        put("tbr", "0")
                    }
                }
            }
            put("cob", loopHub.carbsOnboard)
            // Pilot watch face, time in range ring: % of the returned readings inside the target range.
            val low = loopHub.lowGlucoseMark.takeIf { it > 0.0 } ?: 70.0
            val high = loopHub.highGlucoseMark.takeIf { it > 0.0 } ?: 180.0
            put("tir", calcTirPercent(glucoseValues, low, high))
            // Blueprint / Cockpit / Atelier: target (mg/dL), AIMI mode, TBR minutes left
            loopHub.currentTargetMgdl?.takeIf { it.isFinite() && it > 0.0 }?.let { put("target", it.roundToInt()) }
            loopHub.activeTherapyMode?.takeIf { it.isNotBlank() }?.let { put("mode", it) }
            loopHub.temporaryBasalRemainingMinutes?.takeIf { it > 0 }?.let { put("tbrMins", it) }
            // Millimétré: 3 h basal staircase + SMB times
            putJsonArray("basalHistory") {
                for (step in loopHub.temporaryBasalHistory()) {
                    addJsonObject {
                        put("t", step.t)
                        put("p", step.p)
                    }
                }
            }
            putJsonArray("smbHistory") {
                for (step in loopHub.smbHistory()) {
                    addJsonObject {
                        put("t", step.t)
                        // Two decimal places keep 0.05 U visible on the watch log
                        put("u", round(step.u * 100.0) / 100.0)
                    }
                }
            }
        }
    }

    /** % of the readings inside [low, high] (mg/dL), for the outer ring of the Pilot watch face. */
    private fun calcTirPercent(values: List<GV>, low: Double, high: Double): Int {
        if (values.isEmpty()) return 0
        val lo = minOf(low, high)
        val hi = maxOf(low, high)
        val inRange = values.count { it.value in lo..hi }
        return ((inRange * 100.0) / values.size).roundToInt().coerceIn(0, 100)
    }

    internal companion object {

        const val BOLUS_REFUSED_JSON = "{\"ok\":false}"

        /** A new watch bolus is refused this long after the last one: long enough to stop a retry. */
        const val BOLUS_REPEAT_BLOCK_MS = 60_000L

        /** Keys that give the time of the steps, not a count. */
        val STEP_TIME_KEYS = setOf("stepsStart", "stepsEnd", "stepsstart", "stepsend")

        /** How far back the glucose for the watch goes: 2 h 9 min. */
        const val GLUCOSE_HISTORY_MS = (2L * 60L + 9L) * 60_000L

        const val GLUCOSE_FREQUENCY_MS = 5L * 60_000L

        /**
         * One decimal, with a dot and rounded half up, as the Android plugin wrote it with
         * `String.format(Locale.US, "%.1f")`. Java rounds the shortest decimal text of the number, so
         * this does the same on that text instead of on the binary value.
         */
        fun formatOneDecimal(value: Double): String {
            if (!value.isFinite()) return value.toString()
            val text = value.toString()
            if (text.contains('E') || text.contains('e')) {
                val tenths = floor(value * 10.0 + 0.5).toLong()
                return "${tenths / 10}.${abs(tenths % 10)}"
            }
            val negative = text.startsWith("-")
            val digits = text.removePrefix("-")
            val intPart = digits.substringBefore('.')
            val fraction = digits.substringAfter('.', "")
            var tenths = intPart.toLong() * 10 + (fraction.getOrNull(0)?.digitToInt() ?: 0)
            if ((fraction.getOrNull(1)?.digitToInt() ?: 0) >= 5) tenths++
            val result = "${tenths / 10}.${tenths % 10}"
            return if (negative && tenths != 0L) "-$result" else result
        }
    }
}
