package app.aaps.plugins.source.activities

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.core.os.HandlerCompat
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.configuration.ConfigBuilder
import app.aaps.core.interfaces.source.SensorSlot
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.AapsTopAppBar
import app.aaps.core.ui.compose.LocalPreferences
import app.aaps.core.ui.compose.clearFocusOnTap
import app.aaps.plugins.source.dexcomoneplus.OnePlusCgmDriver
import app.aaps.plugins.source.dexcomoneplus.OnePlusCgmDriverReal
import app.aaps.plugins.source.dexcomoneplus.OnePlusCgmDrivers
import app.aaps.plugins.source.dexcomoneplus.identity.OnePlusAdvCandidate
import app.aaps.plugins.source.dexcomoneplus.identity.OnePlusGs1ApplicatorParser
import app.aaps.plugins.source.dexcomoneplus.identity.OnePlusSensorIdentity
import app.aaps.plugins.source.dexcomoneplus.identity.OnePlusSensorStore
import app.aaps.plugins.source.dexcomoneplus.identity.OnePlusStoredSession
import app.aaps.plugins.source.dexcomoneplus.scan.OnePlusBleScannerAndroid
import app.aaps.plugins.source.dexcomoneplus.scan.OnePlusScanResult
import app.aaps.plugins.source.dexcomoneplus.session.OnePlusSessionStart
import app.aaps.plugins.source.DexcomOnePlusPlugin
import app.aaps.plugins.source.OnePlusBlePermissionHelper
import app.aaps.plugins.source.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Sensor start: BLE permissions, LE scan (DX02/FEBC), applicator GS1 / PIN, connect → warm-up.
 */
@AndroidEntryPoint
class DexcomOnePlusStartActivity : AppCompatActivity() {

    @Inject lateinit var dexcomOnePlusPlugin: DexcomOnePlusPlugin
    @Inject lateinit var configBuilder: ConfigBuilder
    @Inject lateinit var preferences: Preferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dexcomOnePlusPlugin.syncDriverFromPrefs()
        val driver = OnePlusCgmDrivers.default()
        driver.setContext(applicationContext)
        setContent {
            CompositionLocalProvider(LocalPreferences provides preferences) {
                AapsTheme {
                    DexcomOnePlusStartScreen(
                        onBack = { finish() },
                        onEnsureDriver = {
                            dexcomOnePlusPlugin.syncDriverFromPrefs()
                            OnePlusCgmDrivers.default()
                        },
                        onActivatePlugin = {
                            configBuilder.performPluginSwitch(
                                changedPlugin = dexcomOnePlusPlugin,
                                enabled = true,
                                type = PluginType.BGSOURCE,
                            )
                        },
                        onStarted = { startedSlot ->
                            // PRODUCTION: hand the user to the warm-up screen so the new sensor is
                            // visibly taken over (pairing → warm-up countdown). Returning straight to
                            // the dashboard gave no confirmation that Connect had done anything.
                            // STAGING: the warm-up screen tracks the production driver only, so keep
                            // the collect-only flow returning to the dashboard.
                            // The BLE session keeps running on the driver daemon either way; we do
                            // NOT disconnect/shutdown here, only finish this Activity.
                            if (startedSlot == SensorSlot.PRODUCTION) {
                                startActivity(
                                    Intent(this, DexcomOnePlusWarmupActivity::class.java)
                                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                                )
                            }
                            finish()
                        },
                        onBeginStaging = { address -> dexcomOnePlusPlugin.beginStaging(address) },
                        onStagingDriver = { dexcomOnePlusPlugin.stagingDriverForConnect() },
                        onSensorSessionStarted = { address, previousMac ->
                            dexcomOnePlusPlugin.onSensorSessionStarted(address, previousMac)
                        },
                    )
                }
            }
        }
    }
}

/**
 * SharedPreferences namespace of the slot's own sensor store: the staging sensor keeps its identity,
 * MAC and KEKS key completely apart from the production one. null = the original single-sensor file.
 */
private fun SensorSlot.storeNamespace(): String? = OnePlusCgmDrivers.storeNamespace(this)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DexcomOnePlusStartScreen(
    onBack: () -> Unit,
    onEnsureDriver: () -> OnePlusCgmDriver,
    onActivatePlugin: () -> Unit,
    onStarted: (SensorSlot) -> Unit,
    /** STAGING only: the user started this sensor now — anchors the pre-soak clock on its MAC. */
    onBeginStaging: (String) -> Unit,
    onStagingDriver: () -> OnePlusCgmDriverReal,
    /**
     * PRODUCTION only: the user started this sensor now → anchor its age and log a sensor change.
     * Called with (MAC of the started sensor, MAC stored before this start).
     */
    onSensorSessionStarted: (String, String?) -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val mainHandler = remember { HandlerCompat.createAsync(Looper.getMainLooper()) }
    // Which slot the guided flow starts. PRODUCTION keeps the existing behaviour (activates the
    // plugin + feeds the loop after warm-up). STAGING pre-soaks a second sensor collect-only — it
    // never touches the AAPS active BG source until promoted from the status screen.
    var slot by remember { mutableStateOf(SensorSlot.PRODUCTION) }
    // EVERY store read below follows the selected slot. Reading the production store while starting a
    // STAGING sensor pre-filled the old sensor's code and MAC and ranked the scan with the old
    // sensor's fingerprint, so a pre-soak silently re-adopted the sensor already in use (field log
    // 2026-08-11: both slots connecting to the same MAC).
    val sensorStore = remember(slot) { OnePlusSensorStore(context.applicationContext, slot.storeNamespace()) }
    val storedSession = remember(slot) { sensorStore.load() }
    val scanner = remember { OnePlusBleScannerAndroid(context.applicationContext, sessionHint = null) }
    val focusManager = LocalFocusManager.current

    var applicatorInput by remember { mutableStateOf("") }
    var parsedIdentity by remember { mutableStateOf<OnePlusSensorIdentity?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<OnePlusScanResult?>(null) }
    /** The user tapped a sensor in the scan list — auto-select must not overrule that choice. */
    var deviceChosenByUser by remember { mutableStateOf(false) }
    val devices = remember { mutableStateListOf<OnePlusScanResult>() }
    // The applicator field is pre-filled with the *stored* sensor's code. Picking a different MAC
    // while leaving that code untouched pairs a NEW transmitter with the OLD sensor's PIN: auth
    // fails and the slot keeps a session start that is not this sensor's. Warn once, then let the
    // user proceed (a re-typed identical code is legitimate, if unlikely).
    var newSensorConfirmPending by remember { mutableStateOf(false) }

    // Re-seed the whole form from the slot's own store, on first composition and on every slot
    // switch, so what is shown always belongs to the sensor being started.
    LaunchedEffect(slot) {
        scanner.sessionHint = storedSession
        applicatorInput = storedSession?.identity?.rawGs1 ?: storedSession?.identity?.pin.orEmpty()
        parsedIdentity = storedSession?.identity ?: OnePlusGs1ApplicatorParser.parse(applicatorInput)
        selected = storedSession?.lastMac?.let { mac ->
            OnePlusScanResult(address = mac, name = storedSession.lastDeviceName, rssi = 0)
        }
        deviceChosenByUser = false
        devices.clear()
        errorText = null
        newSensorConfirmPending = false
    }

    val codeRequired = stringResource(R.string.dexcom_oneplus_pairing_code_required)
    val codeInvalid = stringResource(R.string.dexcom_oneplus_pairing_code_invalid)
    val permissionsNeeded = stringResource(R.string.dexcom_oneplus_permissions_needed)
    val deviceRequired = stringResource(R.string.dexcom_oneplus_device_required)
    val realSkeletonRequired = stringResource(R.string.dexcom_oneplus_real_skeleton_required)
    val serialNone = stringResource(R.string.dexcom_oneplus_applicator_serial_none)
    val newSensorCodeWarning = stringResource(R.string.dexcom_oneplus_new_sensor_code_warning)
    var connectRequested by remember { mutableStateOf(false) }

    // Guided-flow progress for the stepper: Prepare → Code → Connect → Warm-up.
    val hasPermissions = activity == null || OnePlusBlePermissionHelper.hasAll(activity)
    val hasCode = parsedIdentity != null
    val hasDevice = selected != null
    val currentStep = when {
        !hasPermissions -> 0
        !hasCode         -> 1
        !hasDevice       -> 2
        else             -> 3
    }

    DisposableEffect(Unit) {
        onDispose {
            // Do not stopScan after Connect — StartActivity dispose races Warmup navigation and
            // was killing the driver's pre-connect LE scan on the shared scanner (ADV miss).
            if (!connectRequested) {
                scanner.stopScan()
            }
        }
    }

    // Connect is the step that actually adopts the selected sensor (writes its MAC/PIN into the slot
    // store and starts the session). It is pinned in the Scaffold bottom bar: as a last child of a
    // non-scrolling Column it was pushed off-screen as soon as the scan list filled up, leaving the
    // user with a selected device and no way to validate it — and the slot store still holding the
    // PREVIOUS sensor's MAC.
    val onConnectClick: () -> Unit = onConnectClick@{
        // The real-skeleton gate is a PRODUCTION-only concern (the default driver may be
        // the Stub). The staging driver is always the Real skeleton.
        if (slot == SensorSlot.PRODUCTION && !OnePlusCgmDrivers.useRealSkeleton) {
            errorText = realSkeletonRequired
            return@onConnectClick
        }
        val identity = parsedIdentity
            ?: OnePlusGs1ApplicatorParser.parse(applicatorInput)
        if (identity == null) {
            errorText = codeRequired
            return@onConnectClick
        }
        val code = OnePlusSessionStart.normalizePairingCode(identity.pin)
        if (!OnePlusSessionStart.isValidPairingCode(code)) {
            errorText = codeInvalid
            return@onConnectClick
        }
        if (activity != null && !OnePlusBlePermissionHelper.hasAll(activity)) {
            errorText = permissionsNeeded
            OnePlusBlePermissionHelper.requestMissing(activity)
            return@onConnectClick
        }
        val address = selected?.address
        if (address.isNullOrBlank()) {
            errorText = deviceRequired
            return@onConnectClick
        }
        scanner.stopScan()
        scanning = false
        connectRequested = true
        // Hand off the freshest live sighting (carries seenElapsedMs) so the driver
        // connects in-window instead of blindly re-scanning.
        val sighting = devices.firstOrNull { it.address == address } ?: selected
        if (slot == SensorSlot.STAGING) {
            // Staging: collect-only. Never write the production store, never activate the
            // plugin, never switch the AAPS active BG source — just drive scan/connect on
            // the dedicated staging driver and return to the dashboard.
            onBeginStaging(address)
            val stagingDriver = onStagingDriver()
            stagingDriver.setContext(context.applicationContext)
            stagingDriver.saveIdentity(identity.copy(pin = code))
            stagingDriver.connect(
                deviceAddress = address,
                pairingCode = code,
                sighting = sighting,
            )
            onStarted(SensorSlot.STAGING)
            return@onConnectClick
        }
        // Production: adopting a different transmitter with the pre-filled code of the previous one
        // cannot work (the PIN is per-sensor). Ask for confirmation once before overwriting the slot.
        val storedMac = storedSession?.lastMac
        val adoptingAnotherSensor = storedMac != null && !storedMac.equals(address, ignoreCase = true)
        if (adoptingAnotherSensor && storedSession.identity.pin == code && !newSensorConfirmPending) {
            newSensorConfirmPending = true
            errorText = newSensorCodeWarning
            return@onConnectClick
        }
        sensorStore.saveIdentity(identity.copy(pin = code))
        sensorStore.saveLastMac(address)
        selected?.name?.let { sensorStore.saveLastDeviceName(it) }
        // Anchor the sensor age on this explicit start (dashboard sensor age / SENSOR_CHANGE). After
        // saveIdentity, which drops the stored start when the serial shows another sensor. storedMac
        // is the MAC read at screen entry, so it still describes the sensor that was running.
        onSensorSessionStarted(address, storedMac)
        val activeDriver = onEnsureDriver()
        activeDriver.setContext(context.applicationContext)
        (activeDriver as? OnePlusCgmDriverReal)?.saveIdentity(identity.copy(pin = code))
        if (activeDriver is OnePlusCgmDriverReal) {
            activeDriver.connect(
                deviceAddress = address,
                pairingCode = code,
                sighting = sighting,
            )
        } else {
            activeDriver.connect(deviceAddress = address, pairingCode = code)
        }
        onActivatePlugin()
        onStarted(SensorSlot.PRODUCTION)
    }

    Scaffold(
        topBar = {
            AapsTopAppBar(
                title = { Text(stringResource(R.string.dexcom_oneplus_start_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.dexcom_oneplus_nav_back),
                        )
                    }
                },
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AapsSpacing.extraLarge),
                verticalArrangement = Arrangement.spacedBy(AapsSpacing.small),
            ) {
                errorText?.let { msg ->
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    text = if (slot == SensorSlot.STAGING) {
                        stringResource(R.string.dexcom_oneplus_staging_return_note)
                    } else {
                        stringResource(R.string.dexcom_oneplus_start_return_warmup)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = onConnectClick,
                    modifier = Modifier.fillMaxWidth(),
                    // Selecting a device is what the button acts on: disabled until there is one,
                    // so "nothing happens" is never mistaken for a broken button.
                    enabled = hasDevice,
                ) {
                    Text(
                        if (slot == SensorSlot.STAGING) {
                            stringResource(R.string.dexcom_oneplus_staging_connect)
                        } else {
                            stringResource(R.string.dexcom_oneplus_connect_follow)
                        },
                    )
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(AapsSpacing.extraLarge)
                .clearFocusOnTap(focusManager),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.large),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AapsSpacing.medium),
            ) {
                if (slot == SensorSlot.PRODUCTION) {
                    Button(onClick = { }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.dexcom_oneplus_slot_production))
                    }
                    OutlinedButton(
                        onClick = { slot = SensorSlot.STAGING; errorText = null },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.dexcom_oneplus_slot_staging))
                    }
                } else {
                    OutlinedButton(
                        onClick = { slot = SensorSlot.PRODUCTION; errorText = null },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.dexcom_oneplus_slot_production))
                    }
                    Button(onClick = { }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.dexcom_oneplus_slot_staging))
                    }
                }
            }
            Text(
                text = stringResource(R.string.dexcom_oneplus_slot_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DexcomOnePlusStepper(currentStep = currentStep)
            Text(
                text = stringResource(R.string.dexcom_oneplus_start_steps),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.dexcom_oneplus_permissions_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (activity != null && !OnePlusBlePermissionHelper.hasAll(activity)) {
                Button(
                    onClick = { OnePlusBlePermissionHelper.requestMissing(activity) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.dexcom_oneplus_request_permissions))
                }
            }
            Text(
                text = stringResource(R.string.dexcom_oneplus_dexcom_recovery_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedTextField(
                value = applicatorInput,
                onValueChange = { text ->
                    applicatorInput = text
                    parsedIdentity = OnePlusGs1ApplicatorParser.parse(text)
                    errorText = null
                    newSensorConfirmPending = false
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.dexcom_oneplus_applicator_gs1_label)) },
                supportingText = {
                    val identity = parsedIdentity
                    if (identity != null) {
                        Text(
                            stringResource(
                                R.string.dexcom_oneplus_applicator_parsed,
                                identity.pin,
                                identity.serial ?: serialNone,
                            ),
                        )
                    } else {
                        Text(stringResource(R.string.dexcom_oneplus_applicator_gs1_hint))
                    }
                },
                singleLine = false,
                minLines = 2,
                isError = errorText != null && parsedIdentity == null,
            )
            storedSession?.lastMac?.let { mac ->
                Text(
                    text = stringResource(R.string.dexcom_oneplus_last_mac_hint, mac),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(R.string.dexcom_oneplus_scan_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AapsSpacing.medium),
            ) {
                OutlinedButton(
                    onClick = {
                        if (activity != null && !OnePlusBlePermissionHelper.hasAll(activity)) {
                            errorText = permissionsNeeded
                            OnePlusBlePermissionHelper.requestMissing(activity)
                            return@OutlinedButton
                        }
                        devices.clear()
                        deviceChosenByUser = false
                        scanner.sessionHint = sensorStore.load()
                        scanner.startScan { hit ->
                            mainHandler.post {
                                val idx = devices.indexOfFirst { it.address == hit.address }
                                if (idx >= 0) devices[idx] = hit else devices.add(hit)
                                // Only a suggestion, and only while the user has not chosen: the
                                // ranking scores a match with the STORED sensor far above everything
                                // else, so on a re-scan it kept pulling the selection back to the
                                // sensor already in use instead of the new one the user tapped.
                                if (!deviceChosenByUser) {
                                    autoSelectBest(devices, scanner.sessionHint)?.let { best ->
                                        selected = best
                                    }
                                }
                            }
                        }
                        scanning = true
                        errorText = null
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !scanning,
                ) {
                    Text(stringResource(R.string.dexcom_oneplus_scan_start))
                }
                OutlinedButton(
                    onClick = {
                        scanner.stopScan()
                        scanning = false
                    },
                    modifier = Modifier.weight(1f),
                    enabled = scanning,
                ) {
                    Text(stringResource(R.string.dexcom_oneplus_scan_stop))
                }
            }
            Text(
                text = if (scanning) {
                    stringResource(R.string.dexcom_oneplus_scan_scanning)
                } else {
                    stringResource(R.string.dexcom_oneplus_scan_idle, devices.size)
                },
                style = MaterialTheme.typography.labelMedium,
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = AapsSpacing.xxLarge * 9),
                verticalArrangement = Arrangement.spacedBy(AapsSpacing.small),
            ) {
                val hint = scanner.sessionHint
                val ranked = devices.sortedByDescending {
                    OnePlusAdvCandidate.rankScore(it.name, it.address, it.rssi, hint)
                }
                items(ranked, key = { it.address }) { device ->
                    val selectedHere = selected?.address == device.address
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected = device
                                deviceChosenByUser = true
                                errorText = null
                                newSensorConfirmPending = false
                            }
                            .padding(vertical = AapsSpacing.small),
                    ) {
                        Text(
                            text = device.name ?: stringResource(R.string.dexcom_oneplus_scan_unnamed),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (selectedHere) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        Text(
                            text = stringResource(
                                R.string.dexcom_oneplus_scan_device_line,
                                device.address,
                                device.rssi,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // The real-skeleton gate only applies to the PRODUCTION default driver; the staging driver
            // is always the Real skeleton, so the warning is irrelevant when starting in staging.
            if (slot == SensorSlot.PRODUCTION && !OnePlusCgmDrivers.useRealSkeleton) {
                Text(
                    text = stringResource(R.string.dexcom_oneplus_real_skeleton_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * Compact guided-flow stepper: Prepare → Code → Connect → Dashboard. [currentStep] (0-based) marks
 * how far the user has progressed; reached steps use the theme primary accent, upcoming ones are
 * muted. Purely informational — it never blocks input.
 */
@Composable
private fun DexcomOnePlusStepper(currentStep: Int) {
    val labels = listOf(
        stringResource(R.string.dexcom_oneplus_step_prepare),
        stringResource(R.string.dexcom_oneplus_step_code),
        stringResource(R.string.dexcom_oneplus_step_connect),
        stringResource(R.string.dexcom_oneplus_step_warmup),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AapsSpacing.small),
    ) {
        labels.forEachIndexed { index, label ->
            val reached = index <= currentStep
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AapsSpacing.small),
            ) {
                Box(
                    modifier = Modifier
                        .size(AapsSpacing.chipIconSize)
                        .background(
                            color = if (reached) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            shape = CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (reached) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    color = if (reached) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

private fun autoSelectBest(
    devices: List<OnePlusScanResult>,
    session: OnePlusStoredSession?,
): OnePlusScanResult? {
    if (devices.isEmpty()) return null
    return devices.maxByOrNull {
        OnePlusAdvCandidate.rankScore(it.name, it.address, it.rssi, session)
    }
}
