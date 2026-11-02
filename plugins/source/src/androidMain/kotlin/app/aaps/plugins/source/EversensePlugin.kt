package app.aaps.plugins.source

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationLevel
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.source.BgSource
import app.aaps.core.interfaces.source.EversenseCalibrationSource
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.keys.interfaces.withClick
import app.aaps.core.ui.compose.icons.IcPluginEversense
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.plugins.eversense.EversenseCGMPlugin
import app.aaps.plugins.eversense.callbacks.EversenseWatcher
import app.aaps.plugins.eversense.enums.CalibrationReadiness
import app.aaps.plugins.eversense.enums.EversenseAlarm
import app.aaps.plugins.eversense.enums.EversenseType
import app.aaps.plugins.eversense.models.ActiveAlarm
import app.aaps.plugins.eversense.models.EversenseCGMResult
import app.aaps.plugins.eversense.models.EversenseSecureState
import app.aaps.plugins.eversense.models.EversenseState
import app.aaps.plugins.eversense.util.EversenseCloudClient
import app.aaps.plugins.eversense.util.EversenseHttp365Util
import app.aaps.plugins.eversense.util.EversenseHttpE3Util
import app.aaps.plugins.eversense.util.StorageKeys
import app.aaps.plugins.source.activities.EversenseCalibrationActivity
import app.aaps.plugins.source.activities.EversensePlacementActivity
import app.aaps.plugins.source.activities.EversenseStatusActivity
import app.aaps.plugins.source.activities.RequestEversensePermissionActivity
import app.aaps.plugins.source.compose.BgSourceComposeContent
import app.aaps.plugins.source.keys.EversenseIntentKey
import app.aaps.plugins.source.keys.EversenseStringKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.IntKey as MetroIntKey
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import java.util.Calendar
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

/**
 * Native Eversense E3 and Eversense 365 BG source, driven by the in process BLE driver in
 * `:plugins:eversense`. Ported from dev_OAPSAIMI with the same behaviour.
 *
 * Android only: the driver uses Android BLE and JVM BouncyCastle. The iOS app has no Eversense
 * entry at all, and the [EversenseCalibrationSource] binding there reports itself disabled.
 *
 * It is also the [EversenseCalibrationSource]: the quick launch calibration dialog reaches the
 * transmitter through it. `isEnabled()` comes from [PluginBase]: true when Eversense is the BG
 * source in use.
 */
@ContributesIntoMap(AppScope::class, binding = binding<PluginBase>())
@MetroIntKey(445)
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, binding = binding<EversenseCalibrationSource>())
@Inject
class EversensePlugin(
    rh: ResourceHelper,
    private val context: Context,
    aapsLogger: AAPSLogger,
    preferences: Preferences,
    config: Config,
    notificationManager: NotificationManager,
    private val persistenceLayer: PersistenceLayer
) : AbstractBgSourcePlugin(
    pluginDescription = PluginDescription()
        .mainType(PluginType.BGSOURCE)
        .composeContent { _ ->
            BgSourceComposeContent(
                title = rh.gs(R.string.source_eversense)
            )
        }
        .icon(IcPluginEversense)
        .pluginName(TextRef.AndroidRes(R.string.source_eversense))
        .preferencesVisibleInSimpleMode(false)
        .description(TextRef.AndroidRes(R.string.description_source_eversense)),
    ownPreferences = EversenseIntentKey.entries + EversenseStringKey.entries,
    aapsLogger = aapsLogger,
    rh = rh,
    preferences = preferences,
    config = config,
    notificationManager = notificationManager
), BgSource, EversenseWatcher, EversenseCalibrationSource {

    /** The constructor argument, kept as [ResourceHelper] so Android string ids need no cast. */
    private val resourceHelper: ResourceHelper = rh

    override var sensorBatteryLevel = -1

    private val mainHandler = Handler(Looper.getMainLooper())
    private var ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    private val securePrefs by lazy {
        context.getSharedPreferences(SECURE_PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun cloudUploadEnabled() = preferences.get(BooleanKey.EversenseCloudUploadEnabled)
    private fun cloudUploadToastEnabled() = preferences.get(BooleanKey.EversenseCloudUploadToast)

    private val lastNotifiedFirmwareVersion: String get() = securePrefs.getString("last_notified_firmware_version", "") ?: ""
    private fun setLastNotifiedFirmwareVersion(version: String) = securePrefs.edit(commit = true) { putString("last_notified_firmware_version", version) }
    private fun isSensorExpiryDismissed(insertionDate: Long, days: Int): Boolean =
        securePrefs.getBoolean("eversense_expiry_dismissed_${insertionDate}_${days}", false)

    private fun setSensorExpiryDismissed(insertionDate: Long, days: Int) =
        securePrefs.edit(commit = true) { putBoolean("eversense_expiry_dismissed_${insertionDate}_${days}", true) }

    private fun isCalibrationDueDismissed(nextCalibrationDate: Long): Boolean =
        securePrefs.getBoolean("eversense_cal_due_dismissed_${nextCalibrationDate}", false)

    private fun setCalibrationDueDismissed(nextCalibrationDate: Long) =
        securePrefs.edit(commit = true) { putBoolean("eversense_cal_due_dismissed_${nextCalibrationDate}", true) }

    private fun isBatteryLowDismissed(): Boolean =
        securePrefs.getBoolean("eversense_battery_low_dismissed", false)

    private fun setBatteryLowDismissed() =
        securePrefs.edit(commit = true) { putBoolean("eversense_battery_low_dismissed", true) }

    private var consecutiveNoSignalReadings: Int = 0
    @Volatile private var placementNotificationSnoozed: Boolean = false

    // connectGatt() calls made while the Bluetooth radio is off never fire any callback, so the
    // timer-based reconnect backoff in EversenseGattCallback can silently retry into a dead radio
    // and never recover. This receiver starts a reconnect the moment the adapter is back on. It
    // uses forceReconnect() rather than connect() because Android also does not reliably deliver a
    // disconnect callback when the radio powers off, so the internal "connected" flag may still
    // be true here.
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) == BluetoothAdapter.STATE_ON) {
                aapsLogger.info(LTag.BGSOURCE, "Bluetooth turned back on - forcing Eversense reconnect")
                ioScope.launch {
                    eversense.forceReconnect()
                }
            }
        }
    }

    /**
     * The shared driver, given the application context on first use. dev_OAPSAIMI did this in the
     * constructor; doing it on first use instead means building the plugin (for example in the
     * graph tests) does not touch the Bluetooth stack. Every path in this class goes through here,
     * and the screens are opened through [startActivity], which touches it first.
     */
    private val eversense: EversenseCGMPlugin by lazy {
        EversenseCGMPlugin.instance.also { it.setContext(context, true) }
    }

    override suspend fun onStart() {
        super.onStart()
        ioScope.cancel()
        ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        eversense.addWatcher(this)
        val btFilter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(bluetoothStateReceiver, btFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(bluetoothStateReceiver, btFilter)
        }
        if (hasBluetoothPermissions()) {
            aapsLogger.debug(LTag.BGSOURCE, "onStart - permissions granted, attempting auto-reconnect")
            ioScope.launch {
                eversense.connect(null)
            }
        } else {
            aapsLogger.warn(LTag.BGSOURCE, "Bluetooth permissions not granted - requesting permissions")
            requestBluetoothPermissions()
        }
        syncCredentials()
        // The cloud client secret is put in at build time, never in the source. A build without
        // it cannot sign in, so an Eversense 365 transmitter never pairs. Say so instead of
        // failing quietly.
        if (!EversenseCloudClient.hasClientSecret()) {
            aapsLogger.error(LTag.BGSOURCE, "Eversense: this build has no cloud client secret")
            notificationManager.post(
                NotificationId.EVERSENSE_NO_CLOUD_KEY,
                resourceHelper.gs(R.string.eversense_no_cloud_key),
                level = NotificationLevel.URGENT
            )
        }
    }

    override suspend fun onStop() {
        super.onStop()
        ioScope.cancel()
        eversense.removeWatcher(this)
        notificationManager.dismiss(NotificationId.EVERSENSE_NO_CLOUD_KEY)
        try {
            context.unregisterReceiver(bluetoothStateReceiver)
        } catch (_: IllegalArgumentException) {
            // Not registered - safe to ignore
        }
    }

    private fun requestBluetoothPermissions() {
        val intent = Intent(context, RequestEversensePermissionActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun hasBluetoothPermissions(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
        }

    // ── EversenseCalibrationSource ──

    override fun isConnected(): Boolean = eversense.isConnected()

    override fun isReadyToCalibrate(): Boolean =
        eversense.getCurrentState()?.calibrationReadiness == CalibrationReadiness.READY

    override fun readinessMessage(): String {
        val state = eversense.getCurrentState()
        return when {
            state == null                                            -> resourceHelper.gs(R.string.eversense_not_connected)
            state.calibrationReadiness == CalibrationReadiness.READY -> ""
            else                                                     -> resourceHelper.gs(R.string.eversense_calibration_not_ready)
        }
    }

    override suspend fun calibrate(bgMgDl: Int): Boolean = withContext(Dispatchers.IO) {
        if (bgMgDl < EversenseCalibrationSource.MIN_CALIBRATION_MGDL || bgMgDl > EversenseCalibrationSource.MAX_CALIBRATION_MGDL) {
            // Belt and braces: the dialog already bounds the input. A value outside this range
            // would still be written to the transmitter and would shift every later reading.
            aapsLogger.warn(LTag.BGSOURCE, "Eversense calibration refused: $bgMgDl mg/dL is out of range")
            return@withContext false
        }
        if (!isReadyToCalibrate()) {
            aapsLogger.warn(LTag.BGSOURCE, "Eversense calibration refused: transmitter is not ready")
            return@withContext false
        }
        if (!eversense.isConnected()) {
            aapsLogger.info(LTag.BGSOURCE, "Eversense calibration: not connected, connecting first")
            val ready = withTimeoutOrNull(CALIBRATION_RECONNECT_TIMEOUT_MS) { awaitTransmitterReady() }
            if (ready == null) {
                aapsLogger.warn(LTag.BGSOURCE, "Eversense calibration: connect timed out")
                return@withContext false
            }
        }
        val success = eversense.sendCalibration(bgMgDl)
        aapsLogger.info(LTag.BGSOURCE, "Eversense calibration result: $success")
        if (success) eversense.triggerFullSync(force = true)
        success
    }

    // Turns EversenseWatcher's callback into a one-shot suspend wait. The caller's
    // withTimeoutOrNull cancels it, and invokeOnCancellation takes the watcher back off.
    private suspend fun awaitTransmitterReady(): Boolean = suspendCancellableCoroutine { cont ->
        lateinit var watcher: EversenseWatcher
        watcher = object : EversenseWatcher {
            override fun onTransmitterReady() {
                if (cont.isActive) {
                    eversense.removeWatcher(watcher)
                    cont.resume(true)
                }
            }

            override fun onConnectionChanged(connected: Boolean) {}
            override fun onStateChanged(state: EversenseState) {}
            override fun onCGMRead(type: EversenseType, readings: List<EversenseCGMResult>) {}
            override fun onAlarmReceived(alarm: ActiveAlarm) {}
            override fun onTransmitterNotPlaced() {}
        }
        eversense.addWatcher(watcher)
        cont.invokeOnCancellation { eversense.removeWatcher(watcher) }
        eversense.connect(null)
    }

    /**
     * Copies the user's sign in from the preferences into the driver's own store, or posts the
     * "credentials missing" notification when one of them is empty.
     */
    private fun syncCredentials() {
        val username = preferences.get(EversenseStringKey.EversenseUsername)
        val password = preferences.get(EversenseStringKey.EversensePassword)
        if (username.isEmpty() || password.isEmpty()) {
            notificationManager.post(
                NotificationId.EVERSENSE_CREDENTIALS,
                resourceHelper.gs(R.string.eversense_credentials_missing),
                level = NotificationLevel.URGENT
            )
            return
        }
        val secureState = getSecureState()
        val europeanRegion = preferences.get(BooleanKey.EversenseEuropeanRegion)
        // The region picks which DMS host issued a cached token, so a region change has to clear
        // the token cache exactly like a password change does.
        val credentialsChanged = secureState.username != username || secureState.password != password || secureState.isEuropeanRegion != europeanRegion
        secureState.username = username
        secureState.password = password
        secureState.isEuropeanRegion = europeanRegion
        saveSecureState(secureState)
        eversense.username = username
        eversense.password = password
        if (credentialsChanged) {
            securePrefs.edit(commit = true) {
                remove(StorageKeys.ACCESS_TOKEN)
                remove(StorageKeys.ACCESS_TOKEN_EXPIRY)
            }
            aapsLogger.info(LTag.BGSOURCE, "Eversense: credentials changed - token cache cleared")
        }
        notificationManager.dismiss(NotificationId.EVERSENSE_CREDENTIALS)
    }

    private fun getSecureState(): EversenseSecureState {
        val stateJson = securePrefs.getString(StorageKeys.SECURE_STATE, null) ?: "{}"
        return json.decodeFromString(stateJson)
    }

    private fun saveSecureState(state: EversenseSecureState) {
        securePrefs.edit(commit = true) {
            putString(StorageKeys.SECURE_STATE, json.encodeToString(EversenseSecureState.serializer(), state))
        }
    }

    /**
     * The injected [context] is the application context, which has no Activity theme. AppCompat
     * dialogs need a Theme.AppCompat descendant, so wrap it before building a dialog from a
     * non-Activity context (for example a Compose preference click handler). Otherwise `.show()`
     * throws "You need to use a Theme.AppCompat".
     */
    private fun Context.appCompatDialogContext(): Context =
        ContextThemeWrapper(this, androidx.appcompat.R.style.Theme_AppCompat_DayNight_Dialog_Alert)

    private fun startActivity(activity: Class<*>) {
        // The screens use EversenseCGMPlugin.instance directly, so make sure it has its context.
        eversense
        context.startActivity(Intent(context, activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun getPreferenceScreenContent() = PreferenceSubScreenDef(
        key = "eversense_settings",
        titleResId = R.string.source_eversense,
        summaryResId = R.string.eversense_plugin_summary,
        items = listOf(
            EversenseIntentKey.EversenseAbout.withClick {
                AlertDialog.Builder(context.appCompatDialogContext())
                    .setTitle(resourceHelper.gs(R.string.eversense_about_title))
                    .setMessage(resourceHelper.gs(R.string.eversense_about_detail))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            },
            EversenseIntentKey.EversenseStatus.withClick { startActivity(EversenseStatusActivity::class.java) },
            BooleanKey.EversenseCloudUploadEnabled,
            BooleanKey.EversenseCloudUploadToast,
            PreferenceSubScreenDef(
                key = "eversense_credentials_screen",
                titleResId = R.string.eversense_credentials_title,
                items = listOf(
                    EversenseStringKey.EversenseUsername,
                    EversenseStringKey.EversensePassword,
                    BooleanKey.EversenseEuropeanRegion
                )
            ),
            EversenseIntentKey.EversenseSignOut.withClick {
                val cleared = getSecureState().also {
                    it.username = ""
                    it.password = ""
                }
                saveSecureState(cleared)
                aapsLogger.info(LTag.BGSOURCE, "Eversense credentials cleared by user")
            },
            EversenseIntentKey.EversenseCalibration.withClick { startActivity(EversenseCalibrationActivity::class.java) },
            EversenseIntentKey.EversensePlacement.withClick { startActivity(EversensePlacementActivity::class.java) }
        ),
        icon = pluginDescription.icon
    )

    override fun onStateChanged(state: EversenseState) {
        aapsLogger.info(LTag.BGSOURCE, "New state received: ${Json.encodeToString(state)}")

        // Sensor battery level for the Overview status lights
        sensorBatteryLevel = if (state.batteryPercentage >= 0) state.batteryPercentage else -1

        // Keep the SENSOR_CHANGE therapy event in sync with the transmitter insertion date.
        // Disabled for E3: a forced overwrite every BT cycle injected raw transmitter uptime
        // (subject to deep-sleep pauses), and the home screen timer drifted.
        // E365 keeps the original sync.
        if (state.insertionDate > 0 && eversense.is365()) {
            ioScope.launch {
                persistenceLayer.insertCgmSourceData(Sources.Eversense, emptyList(), emptyList(), state.insertionDate)
                aapsLogger.debug(LTag.BGSOURCE, "Updated SENSOR_CHANGE event to insertionDate: ${state.insertionDate}")
            }
        }

        // Sync the SAGE color thresholds to the Eversense sensor lifetime and notification days
        if (state.insertionDate > 0) {
            val lifetimeDays = if (eversense.is365()) 365 else 180
            val warnHours = (lifetimeDays - 30) * 24   // orange when 30 days remain
            val urgentHours = (lifetimeDays - 10) * 24 // red when 10 days remain
            preferences.put(IntKey.OverviewSageWarning, warnHours)
            preferences.put(IntKey.OverviewSageCritical, urgentHours)
        }

        // A lasting no-signal reading means the transmitter is not placed over the sensor
        if (state.sensorSignalStrength == 0) {
            consecutiveNoSignalReadings++
            aapsLogger.warn(LTag.BGSOURCE, "No signal reading $consecutiveNoSignalReadings of $NO_SIGNAL_WARNING_THRESHOLD")
            if (consecutiveNoSignalReadings >= NO_SIGNAL_WARNING_THRESHOLD) {
                if (!placementNotificationSnoozed) {
                    onTransmitterNotPlaced()
                }
                consecutiveNoSignalReadings = 0
            }
        } else {
            consecutiveNoSignalReadings = 0
            placementNotificationSnoozed = false
            notificationManager.dismiss(NotificationId.EVERSENSE_PLACEMENT)
        }

        // Sensor expiry notifications at 60, 30 and 10 days left: once each, after noon, keyed to insertionDate
        if (state.insertionDate > 0) {
            val sensorLifetimeMs = if (eversense.is365()) 365L * DAY_MS else 180L * DAY_MS
            val expiryMs = state.insertionDate + sensorLifetimeMs
            val daysRemaining = ((expiryMs - System.currentTimeMillis()) / DAY_MS).toInt()
            val isAfterNoon = Calendar.getInstance().get(Calendar.HOUR_OF_DAY) >= 12

            if (isAfterNoon && daysRemaining in 31..60 && !isSensorExpiryDismissed(state.insertionDate, 60)) {
                setSensorExpiryDismissed(state.insertionDate, 60)
                notificationManager.post(
                    NotificationId.EVERSENSE_ALARM,
                    resourceHelper.gs(R.string.eversense_sensor_expiry_plan, daysRemaining),
                    level = NotificationLevel.INFO
                )
            } else if (isAfterNoon && daysRemaining in 11..30 && !isSensorExpiryDismissed(state.insertionDate, 30)) {
                setSensorExpiryDismissed(state.insertionDate, 30)
                notificationManager.post(
                    NotificationId.EVERSENSE_ALARM,
                    resourceHelper.gs(R.string.eversense_sensor_expiry_soon, daysRemaining),
                    level = NotificationLevel.NORMAL
                )
            } else if (isAfterNoon && daysRemaining in 1..10 && !isSensorExpiryDismissed(state.insertionDate, daysRemaining)) {
                setSensorExpiryDismissed(state.insertionDate, daysRemaining)
                notificationManager.post(
                    NotificationId.EVERSENSE_ALARM,
                    resourceHelper.gs(R.string.eversense_sensor_expiry_now, daysRemaining),
                    level = NotificationLevel.URGENT
                )
            }
        }

        // Battery low notification: once, after noon, when the battery is below 11 %
        val isAfterNoonBattery = Calendar.getInstance().get(Calendar.HOUR_OF_DAY) >= 12
        if (isAfterNoonBattery && state.batteryPercentage in 1..10 && !isBatteryLowDismissed()) {
            setBatteryLowDismissed()
            notificationManager.post(
                NotificationId.EVERSENSE_ALARM,
                resourceHelper.gs(R.string.eversense_battery_low, state.batteryPercentage),
                level = NotificationLevel.NORMAL
            )
        }

        // Calibration due notification: once per calendar day
        val calDueDay = (state.nextCalibrationDate / DAY_MS) * DAY_MS
        if (state.nextCalibrationDate > 0
            && System.currentTimeMillis() >= state.nextCalibrationDate
            && !isCalibrationDueDismissed(calDueDay)
        ) {
            setCalibrationDueDismissed(calDueDay)
            notificationManager.post(
                NotificationId.EVERSENSE_ALARM,
                resourceHelper.gs(R.string.eversense_calibration_due),
                level = NotificationLevel.NORMAL
            )
        }

        // Firmware notification: only once per firmware version
        if (state.firmwareVersion.isNotEmpty() && state.firmwareVersion != lastNotifiedFirmwareVersion) {
            setLastNotifiedFirmwareVersion(state.firmwareVersion)
            aapsLogger.info(LTag.BGSOURCE, "Transmitter firmware: ${state.firmwareVersion}")
            notificationManager.post(
                NotificationId.EVERSENSE_FIRMWARE,
                resourceHelper.gs(R.string.eversense_firmware_notice, state.firmwareVersion),
                level = NotificationLevel.INFO
            )
        }
    }

    override fun onTransmitterNotPlaced() {
        aapsLogger.warn(LTag.BGSOURCE, "Transmitter not placed - firing placement warning notification")
        mainHandler.post {
            notificationManager.post(
                NotificationId.EVERSENSE_PLACEMENT,
                resourceHelper.gs(R.string.eversense_transmitter_not_placed),
                level = NotificationLevel.URGENT
            )
        }
    }

    override fun onConnectionChanged(connected: Boolean) {
        aapsLogger.info(LTag.BGSOURCE, "Connection changed - connected: $connected")
    }

    override fun onTransmitterReady() {
        aapsLogger.info(LTag.BGSOURCE, "Transmitter ready - scheduling immediate fullSync on bleExecutor")
        eversense.submitToExecutorAndSync(force = true)
    }

    override fun onAlarmReceived(alarm: ActiveAlarm) {
        aapsLogger.info(LTag.BGSOURCE, "Eversense alarm received: ${alarm.code.title}")
        // CRITICAL_FAULT (code 0) is sent both for hardware faults and for an overdue calibration.
        // If the stored next calibration date has already passed, treat it as a calibration alarm.
        val title = if (alarm.code == EversenseAlarm.CRITICAL_FAULT) {
            val stateJson = securePrefs.getString(StorageKeys.STATE, null)
            val state = stateJson?.let { json.decodeFromString<EversenseState>(it) }
            if (state != null && state.nextCalibrationDate > 0 && state.nextCalibrationDate < System.currentTimeMillis()) {
                resourceHelper.gs(R.string.eversense_calibration_due_now)
            } else {
                alarm.code.title
            }
        } else {
            alarm.code.title
        }
        val level = when {
            alarm.code.isCritical -> NotificationLevel.URGENT
            alarm.code.isWarning  -> NotificationLevel.NORMAL
            else                  -> NotificationLevel.INFO
        }
        mainHandler.post {
            notificationManager.post(
                NotificationId.EVERSENSE_ALARM,
                title,
                level = level
            )
        }
    }

    override fun onCGMRead(type: EversenseType, readings: List<EversenseCGMResult>) {
        val glucoseValues = readings.map { reading ->
            GV(
                timestamp = reading.datetime,
                value = reading.glucoseInMgDl.toDouble(),
                noise = null,
                raw = null,
                trendArrow = TrendArrow.fromString(reading.trend.type),
                sourceSensor = when (type) {
                    EversenseType.EVERSENSE_365 -> SourceSensor.EVERSENSE_365
                    EversenseType.EVERSENSE_E3  -> SourceSensor.EVERSENSE_E3
                }
            )
        }

        ioScope.launch {
            val state = eversense.getCurrentState()
            val insertionDate = state?.insertionDate?.takeIf { it > 0 }
            val result = persistenceLayer.insertCgmSourceData(
                Sources.Eversense,
                glucoseValues,
                listOf(),
                insertionDate
            )
            aapsLogger.info(LTag.BGSOURCE, "CGM insert complete - inserted: ${result.inserted.size}, updated: ${result.updated.size}")

            // Upload the readings to the Eversense cloud, so the official app sees data without BLE
            if (state != null && cloudUploadEnabled()) uploadToCloud(type, readings, state)
        }
    }

    private fun uploadToCloud(type: EversenseType, readings: List<EversenseCGMResult>, state: EversenseState) {
        val prefs = context.getSharedPreferences(SECURE_PREFS_NAME, Context.MODE_PRIVATE)
        // Copy the sign in from the AAPS preferences into SECURE_STATE, where the HTTP helpers read it
        syncCredentials()

        val uploadOk = when (type) {
            EversenseType.EVERSENSE_E3  -> {
                // E3 uses the EU DMS endpoints, and has no PostEssentialLogs (no raw BLE hex on E3)
                val latest = readings.firstOrNull() ?: return
                val portalOk = try {
                    EversenseHttpE3Util.putCurrentValues(
                        preferences = prefs,
                        glucose = latest.glucoseInMgDl,
                        timestamp = latest.datetime,
                        trend = latest.trend,
                        signalStrength = state.sensorSignalStrength,
                        batteryPercentage = state.batteryPercentage
                    )
                } catch (e: Exception) {
                    aapsLogger.error(LTag.BGSOURCE, "Eversense E3 putCurrentValues EXCEPTION: ", e)
                    false
                }
                val eventsOk = try {
                    EversenseHttpE3Util.putDeviceEvents(
                        preferences = prefs,
                        readings = readings,
                        transmitterSerialNumber = state.transmitterSerialNumber
                    )
                } catch (e: Exception) {
                    aapsLogger.error(LTag.BGSOURCE, "Eversense E3 putDeviceEvents EXCEPTION: ", e)
                    false
                }
                aapsLogger.info(LTag.BGSOURCE, "Eversense E3 portal sync ok: $portalOk")
                aapsLogger.info(LTag.BGSOURCE, "Eversense E3 device events ok: $eventsOk")
                portalOk && eventsOk
            }

            EversenseType.EVERSENSE_365 -> try {
                EversenseHttp365Util.uploadGlucoseReadings(
                    preferences = prefs,
                    readings = readings,
                    transmitterSerialNumber = state.transmitterName.ifEmpty { state.transmitterSerialNumber },
                    firmwareVersion = state.firmwareVersion
                )
            } catch (e: Exception) {
                aapsLogger.error(LTag.BGSOURCE, "Eversense uploadGlucoseReadings EXCEPTION: ", e)
                false
            }
        }
        aapsLogger.info(LTag.BGSOURCE, "Eversense cloud upload of ${readings.size} reading(s) ok: $uploadOk")

        if (type == EversenseType.EVERSENSE_365) {
            val latest = readings.firstOrNull { it.rawResponseHex.isNotEmpty() } ?: readings.firstOrNull()
            if (latest != null) {
                val portalOk = EversenseHttp365Util.putCurrentValues(
                    preferences = prefs,
                    glucose = latest.glucoseInMgDl,
                    timestamp = latest.datetime,
                    trend = latest.trend,
                    signalStrength = state.sensorSignalStrength,
                    batteryPercentage = state.batteryPercentage
                )
                aapsLogger.info(LTag.BGSOURCE, "Eversense portal sync ok: $portalOk")
            }

            val uploadableReadings = readings.filter { it.rawResponseHex.isNotEmpty() }
            if (uploadableReadings.isNotEmpty()) {
                val eventsOk = EversenseHttp365Util.putDeviceEvents(
                    preferences = prefs,
                    readings = uploadableReadings,
                    transmitterSerialNumber = state.transmitterSerialNumber
                )
                aapsLogger.info(LTag.BGSOURCE, "Eversense device events ok: $eventsOk")
            }
        }

        if (cloudUploadToastEnabled()) {
            val message =
                if (uploadOk) resourceHelper.gs(R.string.eversense_cloud_upload_ok, readings.size)
                else resourceHelper.gs(R.string.eversense_cloud_upload_failed)
            mainHandler.post {
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {

        /** The driver's own SharedPreferences file. The name is shared with `:plugins:eversense`. */
        private const val SECURE_PREFS_NAME = "EversenseCGMManager"
        private const val NO_SIGNAL_WARNING_THRESHOLD = 3
        private const val CALIBRATION_RECONNECT_TIMEOUT_MS = 30_000L
        private const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
