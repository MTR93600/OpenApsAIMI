package app.aaps.plugins.sync.garmin

import android.content.Context
import androidx.annotation.VisibleForTesting
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginBaseWithPreferences
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.collectResilient
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.icons.IcPluginGarmin
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.plugins.sync.SyncStrings
import app.aaps.plugins.sync.garmin.keys.GarminBooleanKey
import app.aaps.plugins.sync.garmin.keys.GarminIntKey
import app.aaps.plugins.sync.garmin.keys.GarminIntNonKey
import app.aaps.plugins.sync.garmin.keys.GarminLongNonKey
import app.aaps.plugins.sync.garmin.keys.GarminStringKey
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.IntKey
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.runBlocking
import java.net.HttpURLConnection
import java.net.SocketAddress
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.roundToInt

/** Support communication with Garmin devices.
*
* This plugin supports sending glucose values to Garmin devices and receiving
* carbs, heart rate, steps, temporary targets, AIMI `/mode` (sport, and the FCL temporary target)
* and pump disconnect events from the device. It communicates via HTTP on localhost or Garmin's native CIQ library.
*
* On a client it only shows values: carbs and pump connect or disconnect from the
* watch are refused there, see [refuseOnClient].
*/
@ContributesIntoMap(AppScope::class, binding = binding<PluginBase>())
@IntKey(370)
@SingleIn(AppScope::class)
@Inject
class GarminPlugin(
    aapsLogger: AAPSLogger,
    rh: TextResolver,
    preferences: Preferences,
    private val context: Context,
    private val loopHub: LoopHub,
    private val persistenceLayer: PersistenceLayer,
    private val config: Config,
    notificationManager: NotificationManager
) : PluginBaseWithPreferences(
    pluginDescription = PluginDescription()
        .mainType(PluginType.SYNC)
        .icon(IcPluginGarmin)
        .pluginName(SyncStrings.garmin)
        .description(SyncStrings.garmin_description),
    ownPreferences = GarminStringKey.entries + GarminBooleanKey.entries + GarminIntKey.entries +
        GarminIntNonKey.entries + GarminLongNonKey.entries,
    aapsLogger, rh, preferences, notificationManager
) {

    /** HTTP Server for local HTTP server communication (device app requests values) .*/
    private var server: HttpServer? = null

    private companion object {

        const val BOLUS_REFUSED_JSON = "{\"ok\":false}"

        /** A new watch bolus is refused this long after the last one: long enough to stop a retry. */
        const val BOLUS_REPEAT_BLOCK_MS = 60_000L

        /** Keys that give the time of the steps, not a count. */
        val STEP_TIME_KEYS = setOf("stepsStart", "stepsEnd", "stepsstart", "stepsend")
    }

    @VisibleForTesting
    var garminMessengerField: GarminMessenger? = null
    val garminMessenger: GarminMessenger
        get() {
            return synchronized(this) {
                garminMessengerField ?: createGarminMessenger().also { garminMessengerField = it }
            }
        }

    private fun resetGarminMessenger() {
        synchronized(this) {
            garminMessengerField?.dispose()
            garminMessengerField = null
        }
    }

    /** Garmin ConnectIQ application id for native communication. Phone pushes values. */
    private val glucoseAppIds = mapOf(
        "C9E90EE7E6924829A8B45E7DAFFF5CB4" to "GlucoseWatch_Dev",
        "1107CA6C2D5644B998D4BCB3793F2B7C" to "GlucoseDataField_Dev",
        "928FE19A4D3A4259B50CB6F9DDAF0F4A" to "GlucoseWidget_Dev",
        "662DFCF7F5A147DE8BD37F09574ADB11" to "GlucoseWatch",
        "815C7328C21248C493AD9AC4682FE6B3" to "GlucoseDataField",
        "4BDDCC1740084A1FAB83A3B2E2FCF55B" to "GlucoseWidget",
    )

    @VisibleForTesting
    var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @VisibleForTesting
    var clock: Clock = Clock.systemUTC()

    private val valueLock = ReentrantLock()

    @VisibleForTesting
    var newValue: Condition = valueLock.newCondition()
    private var lastGlucoseValueTimestamp: Long? = null
    private val glucoseUnitStr get() = if (loopHub.glucoseUnit == GlucoseUnit.MGDL) "mgdl" else "mmoll"
    private val garminAapsKey get() = preferences.get(GarminStringKey.RequestKey)

    private fun setupGarminMessenger() {
        resetGarminMessenger()
        createGarminMessenger()
    }

    private fun createGarminMessenger(): GarminMessenger {
        val enableDebug = false
        aapsLogger.info(LTag.GARMIN, "initialize IQ messenger in debug=$enableDebug")
        return GarminMessenger(
            aapsLogger, context, glucoseAppIds, { _, _ -> }, true, enableDebug
        )
    }

    override suspend fun onStart() {
        super.onStart()
        aapsLogger.info(LTag.GARMIN, "start")
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        preferences.observe(GarminBooleanKey.LocalHttpServer)
            .drop(1)
            .collectResilient(scope, aapsLogger, LTag.GARMIN) { setupHttpServer() }
        preferences.observe(GarminIntKey.LocalHttpPort)
            .drop(1)
            .collectResilient(scope, aapsLogger, LTag.GARMIN) { setupHttpServer() }
        preferences.observe(GarminStringKey.RequestKey)
            .drop(1)
            .collectResilient(scope, aapsLogger, LTag.GARMIN) { sendPhoneAppMessage() }
        persistenceLayer.observeChanges(GV::class)
            .collectResilient(scope, aapsLogger, LTag.GARMIN, block = ::onNewBloodGlucose)
        setupHttpServer()
        if (garminAapsKey.isNotEmpty())
            setupGarminMessenger()
    }

    private fun setupHttpServer() {
        setupHttpServer(Duration.ZERO)
    }

    @VisibleForTesting
    fun setupHttpServer(wait: Duration) {
        if (preferences.get(GarminBooleanKey.LocalHttpServer)) {
            val port = preferences.get(GarminIntKey.LocalHttpPort)
            if (server != null && server?.port == port) return
            aapsLogger.info(LTag.GARMIN, "starting HTTP server on $port")
            server?.close()
            server = HttpServer(aapsLogger, port).apply {
                registerEndpoint("/get", requestHandler(::onGetBloodGlucose))
                registerEndpoint("/carbs", requestHandler(::onPostCarbs))
                registerEndpoint("/bolus", requestHandler(::onPostBolus))
                registerEndpoint("/temptarget", requestHandler(::onPostTempTarget))
                registerEndpoint("/mode", requestHandler(::onPostMode))
                registerEndpoint("/connect", requestHandler(::onConnectPump))
                registerEndpoint("/sgv.json", requestHandler(::onSgv))
                awaitReady(wait)
            }
        } else if (server != null) {
            aapsLogger.info(LTag.GARMIN, "stopping HTTP server")
            server?.close()
            server = null
        }
    }

    override suspend fun onStop() {
        scope.cancel()
        garminMessengerField?.dispose()
        aapsLogger.info(LTag.GARMIN, "Stop")
        server?.close()
        server = null
        super.onStop()
    }

    /** Receive new blood glucose events.
     *
     * Stores new blood glucose values in lastGlucoseValue to make sure we return
     * these values immediately when values are requested by Garmin device.
     * Sends a message to the Garmin devices via the ciqMessenger. */
    @VisibleForTesting
    fun onNewBloodGlucose(glucoseValues: List<GV>) {
        val timestamp = glucoseValues.maxOfOrNull { it.timestamp } ?: return
        aapsLogger.info(LTag.GARMIN, "onNewBloodGlucose ${Date(timestamp)}")
        valueLock.withLock {
            if ((lastGlucoseValueTimestamp ?: 0) >= timestamp) return
            lastGlucoseValueTimestamp = timestamp
            newValue.signalAll()
        }
    }

    @VisibleForTesting
    fun onConnectDevice(device: GarminDevice) {
        if (garminAapsKey.isNotEmpty()) {
            aapsLogger.info(LTag.GARMIN, "onConnectDevice $device sending glucose")
            sendPhoneAppMessage(device)
        }
    }

    private fun sendPhoneAppMessage(device: GarminDevice) {
        garminMessenger.sendMessage(device, getGlucoseMessage())
    }

    private fun sendPhoneAppMessage() {
        garminMessenger.sendMessage(getGlucoseMessage())
    }

    @VisibleForTesting
    fun getGlucoseMessage() = mapOf<String, Any>(
        "key" to garminAapsKey,
        "command" to "glucose",
        "profile" to loopHub.currentProfileName.first().toString(),
        "encodedGlucose" to encodedGlucose(getGlucoseValues()),
        "remainingInsulin" to loopHub.insulinOnboard,
        "remainingBasalInsulin" to loopHub.insulinBasalOnboard,
        "glucoseUnit" to glucoseUnitStr,
        "temporaryBasalRate" to
            (loopHub.temporaryBasal.takeIf(java.lang.Double::isFinite) ?: 1.0),
        "connected" to loopHub.isConnected,
        "timestamp" to clock.instant().epochSecond
    )

    /** Gets the last 2+ hours of glucose values. */
    @VisibleForTesting
    fun getGlucoseValues(): List<GV> {
        val from = clock.instant().minus(Duration.ofHours(2).plusMinutes(9))
        return loopHub.getGlucoseValues(from, true)
    }

    /** Get the last 2+ hours of glucose values and waits in case a new value should arrive soon. */
    private fun getGlucoseValues(maxWait: Duration): List<GV> {
        val glucoseFrequency = Duration.ofMinutes(5)
        val glucoseValues = getGlucoseValues()
        val last = glucoseValues.lastOrNull() ?: return emptyList()
        val delay = Duration.ofMillis(clock.millis() - last.timestamp)
        return if (!maxWait.isZero
            && delay > glucoseFrequency
            && delay < glucoseFrequency.plusMinutes(1)
        ) {
            valueLock.withLock {
                aapsLogger.debug(LTag.GARMIN, "waiting for new glucose (delay=$delay)")
                newValue.awaitNanos(maxWait.toNanos())
            }
            getGlucoseValues()
        } else {
            glucoseValues
        }
    }

    private fun encodedGlucose(glucoseValues: List<GV>): String {
        val encodedGlucose = DeltaVarEncodedList(glucoseValues.size * 16, 2)
        for (glucose: GV in glucoseValues) {
            val timeSec: Int = (glucose.timestamp / 1000).toInt()
            val glucoseMgDl: Int = glucose.value.roundToInt()
            encodedGlucose.add(timeSec, glucoseMgDl)
        }
        return encodedGlucose.encodedBase64()
    }

    @VisibleForTesting
    fun requestHandler(action: (URI) -> CharSequence) = { caller: SocketAddress, uri: URI, _: String? ->
        val key = garminAapsKey
        val deviceKey = getQueryParameter(uri, "key")
        if (key.isNotEmpty() && key != deviceKey) {
            aapsLogger.warn(LTag.GARMIN, "Invalid AAPS Key from $caller, got '$deviceKey' want '$key' $uri")
            sendPhoneAppMessage()
            Thread.sleep(1000L)
            HttpURLConnection.HTTP_UNAUTHORIZED to "{}"
        } else {
            aapsLogger.info(LTag.GARMIN, "get from $caller resp , req: $uri")
            HttpURLConnection.HTTP_OK to action(uri).also {
                aapsLogger.info(LTag.GARMIN, "get from $caller resp , req: $uri, result: $it")
            }
        }
    }

    /** Responses to get glucose value request by the device.
     *
     * Also, gets the heart rate and steps readings from the device.
     */
    @VisibleForTesting
    fun onGetBloodGlucose(uri: URI): CharSequence {
        receiveHeartRate(uri)
        val profileName = loopHub.currentProfileName
        val waitSec = getQueryParameter(uri, "wait", 0L)
        val glucoseValues = getGlucoseValues(Duration.ofSeconds(waitSec))
        val jo = JsonObject()
        jo.addProperty("encodedGlucose", encodedGlucose(glucoseValues))
        jo.addProperty("remainingInsulin", loopHub.insulinOnboard)
        jo.addProperty("remainingBasalInsulin", loopHub.insulinBasalOnboard)
        loopHub.lowGlucoseMark.takeIf { it > 0.0 }?.let {
            jo.addProperty("lowGlucoseMark", it.roundToInt())
        }
        loopHub.highGlucoseMark.takeIf { it > 0.0 }?.let {
            jo.addProperty("highGlucoseMark", it.roundToInt())
        }
        jo.addProperty("glucoseUnit", glucoseUnitStr)
        loopHub.temporaryBasal.also {
            if (!it.isNaN()) jo.addProperty("temporaryBasalRate", it)
        }
        jo.addProperty("profile", profileName.first().toString())
        jo.addProperty("connected", loopHub.isConnected)
        return jo.toString()
    }

    private fun getQueryParameter(uri: URI, name: String) = (uri.query ?: "")
        .split("&")
        .map { kv -> kv.split("=") }
        .firstOrNull { kv -> kv.size == 2 && kv[0] == name }?.get(1)

    private fun getQueryParameter(
        uri: URI,
        @Suppress("SameParameterValue") name: String,
        @Suppress("SameParameterValue") defaultValue: Boolean
    ): Boolean {
        return when (getQueryParameter(uri, name)?.lowercase()) {
            "true"  -> true
            "false" -> false
            else    -> defaultValue
        }
    }

    private fun getQueryParameter(
        uri: URI, name: String,
        @Suppress("SameParameterValue") defaultValue: Long
    ): Long {
        val value = getQueryParameter(uri, name)
        return try {
            if (value.isNullOrEmpty()) defaultValue else value.toLong()
        } catch (_: NumberFormatException) {
            aapsLogger.error(LTag.GARMIN, "invalid $name value '$value'")
            defaultValue
        }
    }

    private fun getQueryParameter(
        uri: URI,
        @Suppress("SameParameterValue") name: String,
        @Suppress("SameParameterValue") defaultValue: Int
    ): Int {
        val value = getQueryParameter(uri, name)
        return try {
            if (value.isNullOrEmpty()) defaultValue else value.toInt()
        } catch (_: NumberFormatException) {
            aapsLogger.error(LTag.GARMIN, "invalid $name value '$value'")
            defaultValue
        }
    }

    private fun getQueryParameter(
        uri: URI, name: String,
        @Suppress("SameParameterValue") defaultValue: Double
    ): Double {
        val value = getQueryParameter(uri, name)
        return try {
            if (value.isNullOrEmpty()) defaultValue else value.toDouble()
        } catch (_: NumberFormatException) {
            aapsLogger.error(LTag.GARMIN, "invalid $name value '$value'")
            defaultValue
        }
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

    @VisibleForTesting
    fun receiveHeartRate(msg: Map<String, Any>, test: Boolean) {
        val avg: Int = msg.getOrDefault("hr", 0) as Int
        val samplingStartSec: Long = toLong(msg["hrStart"])
        val samplingEndSec: Long = toLong(msg["hrEnd"])
        val device: String? = msg["device"] as String?
        receiveHeartRate(
            Instant.ofEpochSecond(samplingStartSec), Instant.ofEpochSecond(samplingEndSec),
            avg, device, test
        )
        receiveSteps(msg, test)
    }

    @VisibleForTesting
    fun receiveHeartRate(uri: URI) {
        val avg: Int = getQueryParameter(uri, "hr", 0L).toInt()
        val samplingStartSec: Long = getQueryParameter(uri, "hrStart", 0L)
        val samplingEndSec: Long = getQueryParameter(uri, "hrEnd", 0L)
        val device: String? = getQueryParameter(uri, "device")
        receiveHeartRate(
            Instant.ofEpochSecond(samplingStartSec), Instant.ofEpochSecond(samplingEndSec),
            avg, device, getQueryParameter(uri, "test", false)
        )
        receiveSteps(uri)
    }

    private fun receiveHeartRate(
        samplingStart: Instant, samplingEnd: Instant,
        avg: Int, device: String?, test: Boolean
    ) {
        aapsLogger.info(LTag.GARMIN, "average heart rate $avg BPM $samplingStart to $samplingEnd")
        if (test) return
        if (avg > 10 && samplingStart > Instant.ofEpochMilli(0L) && samplingEnd > samplingStart) {
            loopHub.storeHeartRate(samplingStart, samplingEnd, avg, device)
        } else if (avg > 0) {
            aapsLogger.warn(LTag.GARMIN, "Skip saving invalid HR $avg $samplingStart..$samplingEnd")
        }
    }

    @VisibleForTesting
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
                val now = clock.instant().epochSecond
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
            Instant.ofEpochSecond(samplingStartSec), Instant.ofEpochSecond(samplingEndSec),
            steps5, steps10, steps15, steps30, steps60, steps180, device, test,
        )
    }

    @VisibleForTesting
    fun receiveSteps(uri: URI) {
        aapsLogger.debug(LTag.GARMIN, "receiveSteps(HTTP) query: ${uri.query ?: "<empty>"}")
        var samplingStart: Long? = getQueryParameter(uri, "stepsStart")?.toLongOrNull()
        var samplingEnd: Long? = getQueryParameter(uri, "stepsEnd")?.toLongOrNull()
        if (samplingStart == null || samplingEnd == null) {
            if ((uri.query ?: "").contains("steps", ignoreCase = true)) {
                val now = clock.instant().epochSecond
                aapsLogger.warn(LTag.GARMIN, "HTTP steps without a time, using the last 5 minutes")
                samplingStart = now - 300
                samplingEnd = now
            } else {
                return
            }
        }
        val steps5 = getQueryParameter(uri, "steps5")?.toIntOrNull() ?: 0
        val steps10 = getQueryParameter(uri, "steps10")?.toIntOrNull() ?: 0
        val steps15 = getQueryParameter(uri, "steps15")?.toIntOrNull() ?: 0
        val steps30 = getQueryParameter(uri, "steps30")?.toIntOrNull() ?: 0
        val steps60 = getQueryParameter(uri, "steps60")?.toIntOrNull() ?: 0
        val steps180 = getQueryParameter(uri, "steps180")?.toIntOrNull() ?: 0
        val device = getQueryParameter(uri, "device")
        val test = getQueryParameter(uri, "test", false)
        if (steps5 <= 0 && steps10 <= 0 && steps15 <= 0 && steps30 <= 0 && steps60 <= 0 && steps180 <= 0) {
            // Some watch faces (for example Swissalpine) send only the day total as "steps=".
            val totalSteps = getQueryParameter(uri, "steps")?.toIntOrNull() ?: -1
            if (totalSteps >= 0) {
                ingestHttpTotalSteps(device, totalSteps, samplingStart, samplingEnd)
            } else {
                aapsLogger.debug(LTag.GARMIN, "HTTP steps: all buckets are 0, not stored")
            }
            return
        }
        receiveSteps(
            Instant.ofEpochSecond(samplingStart), Instant.ofEpochSecond(samplingEnd),
            steps5, steps10, steps15, steps30, steps60, steps180, device, test,
        )
    }

    /**
     * Turns a day total of steps into the steps since the last total. The first total ever is only
     * kept as the starting point. A total that went down means a new day on the watch: it is stored as
     * it is, because it already holds only today's steps.
     */
    private fun ingestHttpTotalSteps(device: String?, totalSteps: Int, samplingStart: Long, samplingEnd: Long) {
        val now = clock.millis()
        val lastTotal = preferences.get(GarminIntNonKey.LastTotalSteps)
        if (lastTotal < 0) {
            storeLastTotalSteps(totalSteps, now)
            aapsLogger.info(LTag.GARMIN, "HTTP steps: first total $totalSteps kept as the starting point")
            return
        }
        val delta = totalSteps - lastTotal
        val start = Instant.ofEpochSecond(samplingStart)
        val end = Instant.ofEpochSecond(samplingEnd)
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
            val midnight = clock.instant().atZone(ZoneId.systemDefault()).toLocalDate()
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
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

    /** Handles carb notification from the device. */
    @VisibleForTesting
    fun onPostCarbs(uri: URI): CharSequence {
        postCarbs(getQueryParameter(uri, "carbs", 0L).toInt())
        return ""
    }

    private fun postCarbs(carbs: Int) {
        if (carbs > 0) {
            if (config.AAPSCLIENT)
                refuseOnClient(rh.gs(SyncStrings.garmin_carbs_not_on_client, rh.gs(InterfacesStrings.format_carbs, carbs)))
            else
                loopHub.postCarbs(carbs)
        }
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
    @VisibleForTesting
    fun onPostBolus(uri: URI): CharSequence {
        val bolus = getQueryParameter(uri, "bolus", 0.0)
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
        val now = clock.millis()
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

    /** When the last watch bolus was sent on, see [BOLUS_REPEAT_BLOCK_MS]. */
    @Volatile
    private var lastWatchBolusMs: Long? = null

    private fun refuseBolus(text: String) {
        aapsLogger.warn(LTag.GARMIN, text)
        notificationManager.post(id = NotificationId.GARMIN_BOLUS, text = text)
    }

    /**
     * Stores or cancels a temporary target from the watch: `target` in the profile unit, `duration` in
     * minutes. A target or duration of 0 cancels the current one.
     */
    @VisibleForTesting
    fun onPostTempTarget(uri: URI): CharSequence {
        val target = getQueryParameter(uri, "target", 0.0)
        val duration = getQueryParameter(uri, "duration", 0)
        loopHub.postTempTarget(target, duration)
        return ""
    }

    /**
     * Activates an AIMI mode from the watch.
     * NOTE text = keyword only; duration query sets TherapyEvent.duration.
     * FCL also starts a temporary target 80 mg/dL for 30 minutes.
     * Query: /mode?mode=lunch&duration=60&key=...
     */
    @VisibleForTesting
    fun onPostMode(uri: URI): CharSequence {
        val decision = GarminTherapyMode.decide(
            getQueryParameter(uri, "mode"),
            getQueryParameter(uri, "duration"),
        )
        return when (decision) {
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

    /** Handles pump connected notification that the user entered on the Garmin device. */
    @VisibleForTesting
    fun onConnectPump(uri: URI): CharSequence {
        val minutes = getQueryParameter(uri, "disconnectMinutes", 0L).toInt()
        if (config.AAPSCLIENT) {
            refuseOnClient(rh.gs(SyncStrings.garmin_pump_not_on_client))
        } else if (minutes > 0) {
            loopHub.disconnectPump(minutes)
        } else {
            loopHub.connectPump()
        }

        val jo = JsonObject()
        jo.addProperty("connected", loopHub.isConnected)
        return jo.toString()
    }

    /**
     * A client only shows values on the watch. Carbs or a pump disconnect stored here would stay on the client and
     * never reach the master, so they are not used. The watch cannot show why, so the phone says it.
     */
    private fun refuseOnClient(text: String) {
        aapsLogger.warn(LTag.GARMIN, "not used on a client: $text")
        notificationManager.post(id = NotificationId.GARMIN_NOT_ON_CLIENT, text = text)
    }

    private fun glucoseSlopeMgDlPerMilli(glucose1: GV, glucose2: GV): Double {
        return (glucose2.value - glucose1.value) / (glucose2.timestamp - glucose1.timestamp)
    }

    /** Returns glucose values in Nightscout/Xdrip format. */
    @VisibleForTesting
    fun onSgv(uri: URI): CharSequence {
        receiveHeartRate(uri)
        val count = getQueryParameter(uri, "count", 24L)
            .toInt().coerceAtMost(1000).coerceAtLeast(1)
        val briefMode = getQueryParameter(uri, "brief_mode", false)

        // Guess a start time to get [count+1] readings. This is a heuristic that only works if we get readings
        // every 5 minutes and we're not missing readings. We truncate in case we get more readings but we'll
        // get less, e.g., in case we're missing readings for the last half hour. We get one extra reading,
        // to compute the glucose delta.
        val from = clock.instant().minus(Duration.ofMinutes(5L * (count + 1)))
        val glucoseValues = loopHub.getGlucoseValues(from, false)
        val joa = JsonArray()
        for (i in 0 until count.coerceAtMost(glucoseValues.size)) {
            val jo = JsonObject()
            val glucose = glucoseValues[i]
            if (!briefMode) {
                jo.addProperty("_id", glucose.id.toString())
                jo.addProperty("device", glucose.sourceSensor.toString())
                val timestamp = Instant.ofEpochMilli(glucose.timestamp)
                jo.addProperty("deviceString", timestamp.toString())
                jo.addProperty("sysTime", timestamp.toString())
                glucose.raw?.let { raw -> jo.addProperty("unfiltered", raw) }
            }
            jo.addProperty("date", glucose.timestamp)
            jo.addProperty("sgv", glucose.value.roundToInt())
            if (i + 1 < glucoseValues.size) {
                // The 5 minute delta in mg/dL, rounded to a whole number so that a change of 1 is
                // still there after the watch's `toNumber()` or `format("%.0f")`.
                val delta = 300_000.0 * glucoseSlopeMgDlPerMilli(glucoseValues[i + 1], glucose)
                if (delta.isFinite()) {
                    jo.addProperty("delta", delta.roundToInt())
                }
            }
            jo.addProperty("direction", glucose.trendArrow.text)
            glucose.noise?.let { n -> jo.addProperty("noise", n) }
            if (i == 0) {
                when (loopHub.glucoseUnit) {
                    GlucoseUnit.MGDL -> jo.addProperty("units_hint", "mgdl")
                    GlucoseUnit.MMOL -> jo.addProperty("units_hint", "mmol")
                }
                jo.addProperty("iob", loopHub.insulinOnboard + loopHub.insulinBasalOnboard)
                // Nightscout style "U/h/%", so the watch can show the rate and the percent.
                // temporaryBasal is a factor (1.0 = 100 %). NaN: no property, the watch keeps its last value.
                loopHub.temporaryBasal.also { tbrFactor ->
                    if (!tbrFactor.isNaN()) {
                        val safeFactor = if (tbrFactor.isFinite()) tbrFactor else 0.0
                        val profileBasal = loopHub.currentProfile?.getBasal() ?: 0.0
                        val rateUph = profileBasal * safeFactor
                        val percent = (safeFactor * 100.0).toInt()
                        if (rateUph != 0.0 || percent != 0) {
                            jo.addProperty("tbr", String.format(Locale.US, "%.1f/%d", rateUph, percent))
                        } else {
                            jo.addProperty("tbr", "0")
                        }
                    }
                }
                jo.addProperty("cob", loopHub.carbsOnboard)
                // Pilot watch face, time in range ring: % of the returned readings inside the target range.
                val low = loopHub.lowGlucoseMark.takeIf { it > 0.0 } ?: 70.0
                val high = loopHub.highGlucoseMark.takeIf { it > 0.0 } ?: 180.0
                jo.addProperty("tir", calcTirPercent(glucoseValues, low, high))
                // Blueprint / Cockpit / Atelier: target (mg/dL), AIMI mode, TBR minutes left
                loopHub.currentTargetMgdl?.takeIf { it.isFinite() && it > 0.0 }?.let {
                    jo.addProperty("target", it.roundToInt())
                }
                loopHub.activeTherapyMode?.takeIf { it.isNotBlank() }?.let {
                    jo.addProperty("mode", it)
                }
                loopHub.temporaryBasalRemainingMinutes?.takeIf { it > 0 }?.let {
                    jo.addProperty("tbrMins", it)
                }
                // Millimétré: 3 h basal staircase + SMB times
                val basalArr = JsonArray()
                for (step in loopHub.temporaryBasalHistory()) {
                    val s = JsonObject()
                    s.addProperty("t", step.t)
                    s.addProperty("p", step.p)
                    basalArr.add(s)
                }
                jo.add("basalHistory", basalArr)
                val smbArr = JsonArray()
                for (step in loopHub.smbHistory()) {
                    val s = JsonObject()
                    s.addProperty("t", step.t)
                    // Two decimal places keep 0.05 U visible on the watch log
                    s.addProperty("u", kotlin.math.round(step.u * 100.0) / 100.0)
                    smbArr.add(s)
                }
                jo.add("smbHistory", smbArr)
            }
            joa.add(jo)
        }
        return joa.toString()
    }

    /** % of the readings inside [low, high] (mg/dL), for the outer ring of the Pilot watch face. */
    private fun calcTirPercent(values: List<GV>, low: Double, high: Double): Int {
        if (values.isEmpty()) return 0
        val lo = minOf(low, high)
        val hi = maxOf(low, high)
        val inRange = values.count { it.value in lo..hi }
        return ((inRange * 100.0) / values.size).roundToInt().coerceIn(0, 100)
    }

    override fun getPreferenceScreenContent() = PreferenceSubScreenDef(
        key = "garmin_settings",
        title = SyncStrings.garmin,
        items = listOf(
            GarminBooleanKey.LocalHttpServer,
            GarminIntKey.LocalHttpPort,
            GarminStringKey.RequestKey

        ),
        icon = pluginDescription.icon
    )

}
