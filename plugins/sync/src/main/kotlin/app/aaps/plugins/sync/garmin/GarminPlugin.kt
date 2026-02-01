package app.aaps.plugins.sync.garmin

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.PluginBaseWithPreferences
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventNewBG
import app.aaps.core.interfaces.rx.events.EventPreferenceChange
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.validators.DefaultEditTextValidator
import app.aaps.core.validators.preferences.AdaptiveIntPreference
import app.aaps.core.validators.preferences.AdaptiveStringPreference
import app.aaps.core.validators.preferences.AdaptiveSwitchPreference
import app.aaps.plugins.sync.R
import app.aaps.plugins.sync.garmin.keys.GarminBooleanKey
import app.aaps.plugins.sync.garmin.keys.GarminIntKey
import app.aaps.plugins.sync.garmin.keys.GarminStringKey
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.net.HttpURLConnection
import java.net.SocketAddress
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock
import kotlin.math.roundToInt
import app.aaps.core.interfaces.sharedPreferences.SP

/** Support communication with Garmin devices.
 *
 * This plugin supports sending glucose values to Garmin devices and receiving
 * carbs, heart rate and pump disconnect events from the device. It communicates
 * via HTTP on localhost or Garmin's native CIQ library.
 */
@Singleton
class GarminPlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    resourceHelper: ResourceHelper,
    preferences: Preferences,
    private val sp: SP,
    private val context: Context,
    private val loopHub: LoopHub,
    private val rxBus: RxBus
) : PluginBaseWithPreferences(
    pluginDescription = PluginDescription()
        .mainType(PluginType.SYNC)
        .pluginIcon(app.aaps.core.objects.R.drawable.ic_watch)
        .pluginName(R.string.garmin)
        .shortName(R.string.garmin)
        .description(R.string.garmin_description)
        .preferencesId(PluginDescription.PREFERENCE_SCREEN),
    ownPreferences = listOf(GarminStringKey::class.java, GarminBooleanKey::class.java, GarminIntKey::class.java),
    aapsLogger, resourceHelper, preferences
) {

    /** HTTP Server for local HTTP server communication (device app requests values) .*/
    private var server: HttpServer? = null

    companion object {
        private const val PREF_GARMIN_LAST_STEPS = "garmin_http_last_steps"
        private const val PREF_GARMIN_LAST_TS = "garmin_http_last_steps_ts"
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
    private val disposable = CompositeDisposable()

    @VisibleForTesting
    var clock: Clock = Clock.systemUTC()

    private val valueLock = ReentrantLock()

    @VisibleForTesting
    var newValue: Condition = valueLock.newCondition()
    private var lastGlucoseValueTimestamp: Long? = null
    private val glucoseUnitStr get() = if (loopHub.glucoseUnit == GlucoseUnit.MGDL) "mgdl" else "mmoll"
    private val garminAapsKey get() = preferences.get(GarminStringKey.RequestKey)

    private fun onPreferenceChange(event: EventPreferenceChange) {
        when (event.changedKey) {
            "communication_ciq_debug_mode"                                       -> setupGarminMessenger()
            GarminBooleanKey.LocalHttpServer.key, GarminIntKey.LocalHttpPort.key -> setupHttpServer()
            GarminStringKey.RequestKey.key                                       -> sendPhoneAppMessage()
        }
    }

    private fun setupGarminMessenger() {
        resetGarminMessenger()
        createGarminMessenger()
    }

    private fun createGarminMessenger(): GarminMessenger {
        val enableDebug = false // sp.getBoolean("communication_ciq_debug_mode", false)
        aapsLogger.info(LTag.GARMIN, "initialize IQ messenger in debug=$enableDebug")
        return GarminMessenger(
            aapsLogger, context, glucoseAppIds, 
            { app, msg -> 
                // Native ConnectIQ Message Handling (Bluetooth Sync)
                if (msg is Map<*, *>) {
                    try {
                        @Suppress("UNCHECKED_CAST")
                        val map = msg as? Map<String, Any>
                        if (map != null) {
                            aapsLogger.debug(LTag.GARMIN, "Received Native Message from ${app.id}: $map")
                            receiveHeartRate(map, false)
                            receiveSteps(map, false)  // ✅ CRITICAL FIX: Process steps from CIQ message
                        }
                    } catch (e: Exception) {
                        aapsLogger.error(LTag.GARMIN, "Error processing native message: ${e.message}")
                    }
                }
            }, 
            true, enableDebug
        ).also {
            disposable.add(it)
        }
    }

    override fun onStart() {
        super.onStart()
        aapsLogger.info(LTag.GARMIN, "start")
        disposable.add(
            rxBus
                .toObservable(EventPreferenceChange::class.java)
                .observeOn(Schedulers.io())
                .subscribe(::onPreferenceChange)
        )
        disposable.add(
            rxBus
                .toObservable(EventNewBG::class.java)
                .observeOn(Schedulers.io())
                .subscribe(::onNewBloodGlucose)
        )
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

    override fun onStop() {
        disposable.clear()
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
    fun onNewBloodGlucose(event: EventNewBG) {
        val timestamp = event.glucoseValueTimestamp ?: return
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


    // mod Bolus and temp target
    private fun getQueryParameter(
        uri: URI, name: String,
        @Suppress("SameParameterValue") defaultValue: Int
    ): Int {
        val value = getQueryParameter(uri, name)
        return try {
            if (value.isNullOrEmpty()) defaultValue else value.toInt()
        } catch (e: NumberFormatException) {
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
        } catch (e: NumberFormatException) {
            aapsLogger.error(LTag.GARMIN, "invalid $name value '$value'")
            defaultValue
        }
    }
    // end mod

    private fun toLong(v: Any?): Long {
        return when (v) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull() ?: 0L
            else -> 0L
        }
    }
    private fun toInt(v: Any?) = when (v) {
        is Number -> v.toInt()
        is String -> v.toDoubleOrNull()?.toInt()
        else -> null
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
        // 🔍 DIAGNOSTIC: Log what Garmin sends
        aapsLogger.debug(LTag.GARMIN, "receiveSteps() - Keys received: ${msg.keys.joinToString(", ")}")
        
        // 1. Extract timestamps (robust parsing - handles String and Number)
        var samplingStartSec = toLong(msg["stepsStart"])
        var samplingEndSec = toLong(msg["stepsEnd"])
        
        // 🔧 FALLBACK 1: Try case-insensitive variants
        if (samplingStartSec == 0L) samplingStartSec = toLong(msg["stepsstart"])
        if (samplingEndSec == 0L) samplingEndSec = toLong(msg["stepsend"])
        
        // 🔧 FALLBACK 2: If timestamps missing, use current time - 5min window
        if (samplingStartSec == 0L || samplingEndSec == 0L) {
            if (msg.keys.any { it.contains("steps", ignoreCase = true) && it !in listOf("stepsStart", "stepsEnd", "stepsstart", "stepsend") }) {
                val now = clock.instant().epochSecond
                aapsLogger.warn(LTag.GARMIN, "Steps data without timestamps. Using fallback: now-5min to now. Keys: ${msg.keys.joinToString(",")}")
                samplingStartSec = now - 300
                samplingEndSec = now
            } else {
                return // No steps data at all
            }
        }

        // 2. Lenient Bucket Retrieval
        val steps5 = toInt(msg["steps5"]) ?: 0
        val steps10 = toInt(msg["steps10"]) ?: 0
        val steps15 = toInt(msg["steps15"]) ?: 0
        val steps30 = toInt(msg["steps30"]) ?: 0
        val steps60 = toInt(msg["steps60"]) ?: 0
        val steps180 = toInt(msg["steps180"]) ?: 0
        val device: String? = msg["device"] as String?
        
        // 3. Validation & Logging
        val hasData = steps5 > 0 || steps10 > 0 || steps15 > 0 || steps30 > 0 || steps60 > 0 || steps180 > 0
        
        if (!hasData) {
            aapsLogger.debug(LTag.GARMIN, "Steps: All buckets are 0. Skipping.")
            return
        }
        
        aapsLogger.info(LTag.GARMIN, "Steps: 5=$steps5, 10=$steps10, 15=$steps15, 30=$steps30, 60=$steps60, 180=$steps180")
        
        if (hasData) {
            receiveSteps(
                Instant.ofEpochSecond(samplingStartSec),
                Instant.ofEpochSecond(samplingEndSec),
                steps5,
                steps10,
                steps15,
                steps30,
                steps60,
                steps180,
                device,
                test,
            )
        }
    }

    @VisibleForTesting
    fun receiveSteps(uri: URI) {
        // 🔍 DIAGNOSTIC
        aapsLogger.debug(LTag.GARMIN, "receiveSteps(HTTP) - Query: ${uri.query ?: "<empty>"}")
        
        // 1. Extract timestamps with fallbacks
        var samplingStart: Long? = getQueryParameter(uri, "stepsStart")?.toLongOrNull()
        var samplingEnd: Long? = getQueryParameter(uri, "stepsEnd")?.toLongOrNull()
        
        // 🔧 FALLBACK: Use current time if missing
        if (samplingStart == null || samplingEnd == null) {
            if ((uri.query ?: "").contains("steps", ignoreCase = true)) {
                val now = clock.instant().epochSecond
                aapsLogger.warn(LTag.GARMIN, "HTTP steps without timestamps. Using fallback: now-5min to now")
                samplingStart = now - 300
                samplingEnd = now
            } else {
                return
            }
        }
        
        // 2. Lenient Bucket Retrieval (Default to 0 if missing)
        // This allows watchfaces to send only partial data (e.g. only steps5) without failing
        val steps5 = getQueryParameter(uri, "steps5")?.toIntOrNull() ?: 0
        val steps10 = getQueryParameter(uri, "steps10")?.toIntOrNull() ?: 0
        val steps15 = getQueryParameter(uri, "steps15")?.toIntOrNull() ?: 0
        val steps30 = getQueryParameter(uri, "steps30")?.toIntOrNull() ?: 0
        val steps60 = getQueryParameter(uri, "steps60")?.toIntOrNull() ?: 0
        val steps180 = getQueryParameter(uri, "steps180")?.toIntOrNull() ?: 0
        val device = getQueryParameter(uri, "device")
        val test = getQueryParameter(uri, "test", false)

        // 3. Validation & Logging
        val hasData = steps5 > 0 || steps10 > 0 || steps15 > 0 || steps30 > 0 || steps60 > 0 || steps180 > 0
        
        if (!hasData) {
            //Fix Garmin sending only "steps=xxx"
            val totalSteps = getQueryParameter(uri, "steps")?.toIntOrNull() ?: -1
            if (totalSteps >= 0 ) {
                ingestHttpTotalSteps(uri, totalSteps)
                return
            }

            aapsLogger.debug(LTag.GARMIN, "HTTP Steps: All buckets are 0. Skipping.")
            return
        }
        
        aapsLogger.info(LTag.GARMIN, "HTTP Steps: 5=$steps5, 10=$steps10, 15=$steps15, 30=$steps30, 60=$steps60, 180=$steps180")
        
        if (hasData) {
            receiveSteps(
                Instant.ofEpochSecond(samplingStart),
                Instant.ofEpochSecond(samplingEnd),
                steps5,
                steps10,
                steps15,
                steps30,
                steps60,
                steps180,
                device,
                test,
            )
        } else {
             // Low-level debug only to avoid spamming if watchface sends empty heartbeat
             // aapsLogger.debug(LTag.GARMIN, "Received steps timestamp but all buckets are 0/missing")
        }
    }

    fun ingestHttpTotalSteps(uri: URI, totalSteps: Int) {
        val device = getQueryParameter(uri, "device")

        val now = System.currentTimeMillis()
        val lastTotal = sp.getInt(PREF_GARMIN_LAST_STEPS, -1)

        // First ever value → store baseline only
        if (lastTotal < 0) {
            sp.putInt(PREF_GARMIN_LAST_STEPS, totalSteps)
            sp.putLong(PREF_GARMIN_LAST_TS, now)
            aapsLogger.info(LTag.GARMIN, "[GarminHTTP] baseline steps=$totalSteps")
            return
        }

        val delta = totalSteps - lastTotal

        // Guard rails: Only strict check is that delta must be positive.
        // We remove the 3000 upper limit because during a long run (e.g. 1h without sync), 
        // the delta can easily exceed 3000 steps.
        if (delta <= 0) {
            aapsLogger.warn(
                LTag.GARMIN,
                "[GarminHTTP] invalid step delta=$delta (total=$totalSteps last=$lastTotal) => must be > 0"
            )
            // If delta is 0 or negative (device reset?), we just update the last known value to sync up
            if (totalSteps > 0) {
                sp.putInt(PREF_GARMIN_LAST_STEPS, totalSteps)
                sp.putLong(PREF_GARMIN_LAST_TS, now)
            }
            return
        }

        val start = Instant.ofEpochMilli(now - 5 * 60_000)
        val end = Instant.ofEpochMilli(now)
        val none = 0

        loopHub.storeStepsCount(
            start,
            end,
            delta,
            none,
            none,
            none,
            none,
            none,
            device
        )

        aapsLogger.info(
            LTag.GARMIN,
            "[GarminHTTP] steps delta=$delta (${start} → ${end}) Total: $totalSteps"
        )

        sp.putInt(PREF_GARMIN_LAST_STEPS, totalSteps)
        sp.putLong(PREF_GARMIN_LAST_TS, now)
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
        aapsLogger.info(
            LTag.GARMIN,
            "steps $steps5/$steps10/$steps15/$steps30/$steps60/$steps180 from $samplingStart to $samplingEnd",
        )
        if (test) return
        if (samplingEnd > samplingStart) {
            loopHub.storeStepsCount(
                samplingStart,
                samplingEnd,
                steps5,
                steps10,
                steps15,
                steps30,
                steps60,
                steps180,
                device,
            )
        } else {
            aapsLogger.warn(
                LTag.GARMIN,
                "Skip saving invalid steps period $samplingStart..$samplingEnd",
            )
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
            loopHub.postCarbs(carbs)
        }
    }

    // mod Post bolus and temp targets
    private fun onPostBolus(uri: URI): CharSequence {
        val bolus: Double = getQueryParameter(uri, "bolus", 0.0)
        loopHub.postBolus(bolus)
        return ""
    }

    /** Handles temp targets from the device. */
    fun onPostTempTarget(uri: URI): CharSequence {
        val target: Double = getQueryParameter(uri, "target", 0.0)
        val duration: Int = getQueryParameter(uri, "duration", 0)
        loopHub.postTempTarget(target, duration)
        return ""
    }
    // end mod

    /** Handles pump connected notification that the user entered on the Garmin device. */
    @VisibleForTesting
    fun onConnectPump(uri: URI): CharSequence {
        val minutes = getQueryParameter(uri, "disconnectMinutes", 0L).toInt()
        if (minutes > 0) {
            loopHub.disconnectPump(minutes)
        } else {
            loopHub.connectPump()
        }

        val jo = JsonObject()
        jo.addProperty("connected", loopHub.isConnected)
        return jo.toString()
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
                // Compute the 5 minute delta.
                val delta = 300_000.0 * glucoseSlopeMgDlPerMilli(glucoseValues[i + 1], glucose)
                jo.addProperty("delta", BigDecimal(delta, MathContext(3, RoundingMode.HALF_UP)))
            }
            jo.addProperty("direction", glucose.trendArrow.text)
            glucose.noise?.let { n -> jo.addProperty("noise", n) }
            if (i == 0) {
                when (loopHub.glucoseUnit) {
                    GlucoseUnit.MGDL -> jo.addProperty("units_hint", "mgdl")
                    GlucoseUnit.MMOL -> jo.addProperty("units_hint", "mmol")
                }
                jo.addProperty("iob", loopHub.insulinOnboard + loopHub.insulinBasalOnboard)
                loopHub.temporaryBasal.also {
                    if (!it.isNaN()) {
                        val temporaryBasalRateInPercent = (it * 100.0).toInt()
                        jo.addProperty("tbr", temporaryBasalRateInPercent)
                    }
                }
                jo.addProperty("cob", loopHub.carbsOnboard)
            }
            joa.add(jo)
        }
        return joa.toString()
    }

    override fun addPreferenceScreen(preferenceManager: PreferenceManager, parent: PreferenceScreen, context: Context, requiredKey: String?) {
        if (requiredKey != null) return
        val category = PreferenceCategory(context)
        parent.addPreference(category)
        category.apply {
            key = "garmin_settings"
            title = rh.gs(R.string.garmin)
            initialExpandedChildrenCount = 0
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = GarminBooleanKey.LocalHttpServer, title = R.string.garmin_local_http_server))
            addPreference(AdaptiveIntPreference(ctx = context, intKey = GarminIntKey.LocalHttpPort, title = R.string.garmin_local_http_server_port))
            addPreference(
                AdaptiveStringPreference(
                    ctx = context,
                    stringKey = GarminStringKey.RequestKey,
                    title = R.string.garmin_request_key,
                    summary = R.string.garmin_request_key_summary,
                    validatorParams = DefaultEditTextValidator.Parameters(emptyAllowed = true)
                )
            )
        }
    }
}
