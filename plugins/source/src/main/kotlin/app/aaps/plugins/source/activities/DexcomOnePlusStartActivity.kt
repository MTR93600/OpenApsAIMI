package app.aaps.plugins.source.activities

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.os.HandlerCompat
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.configuration.ConfigBuilder
import app.aaps.core.interfaces.source.SensorSlot
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.LocalPreferences
import app.aaps.core.ui.compose.clearFocusOnTap
import app.aaps.plugins.source.dexcomoneplus.OnePlusCgmDriver
import app.aaps.plugins.source.dexcomoneplus.OnePlusCgmDriverReal
import app.aaps.plugins.source.dexcomoneplus.OnePlusCgmDrivers
import app.aaps.plugins.source.dexcomoneplus.identity.OnePlusAdvCandidate
import app.aaps.plugins.source.dexcomoneplus.identity.OnePlusGs1ApplicatorParser
import app.aaps.plugins.source.dexcomoneplus.identity.OnePlusSensorIdentity
import app.aaps.plugins.source.dexcomoneplus.identity.OnePlusSensorStore
import app.aaps.plugins.source.dexcomoneplus.scan.OnePlusBleScannerAndroid
import app.aaps.plugins.source.dexcomoneplus.scan.OnePlusScanResult
import app.aaps.plugins.source.dexcomoneplus.session.OnePlusSessionStart
import app.aaps.plugins.source.DexcomOnePlusPlugin
import app.aaps.plugins.source.OnePlusBlePermissionHelper
import app.aaps.plugins.source.R
import app.aaps.plugins.source.compose.CgmCard
import app.aaps.plugins.source.compose.CgmCardHeader
import app.aaps.plugins.source.compose.CgmCardTone
import app.aaps.plugins.source.compose.CgmHelpCard
import app.aaps.plugins.source.compose.CgmLazyColumn
import app.aaps.plugins.source.compose.CgmNavIcon
import app.aaps.plugins.source.compose.CgmScaffold
import app.aaps.plugins.source.compose.CgmStateChip
import app.aaps.plugins.source.compose.CgmStepper
import app.aaps.plugins.source.compose.CgmUiState
import app.aaps.plugins.source.compose.CgmWidth
import app.aaps.plugins.source.compose.CgmWindow
import app.aaps.plugins.source.compose.rememberCgmWindow
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
                        onIsStagingSensor = { address -> dexcomOnePlusPlugin.isStagingSensor(address) },
                        onIsProductionSensor = { address -> dexcomOnePlusPlugin.isProductionSensor(address) },
                        onCancelStaging = { dexcomOnePlusPlugin.cancelStaging() },
                        // Promotion has its own gates and its own confirmation, and they live on the
                        // status screen. Sending the user there is honest; re-implementing the gate
                        // here would be a second place to get a loop source switch wrong.
                        onOpenPromote = {
                            startActivity(
                                Intent(this, DexcomOnePlusStatusActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                            )
                            finish()
                        },
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

@Composable
private fun DexcomOnePlusStartScreen(
    onBack: () -> Unit,
    onEnsureDriver: () -> OnePlusCgmDriver,
    onActivatePlugin: () -> Unit,
    onStarted: (SensorSlot) -> Unit,
    /** STAGING only: the user started this sensor now — anchors the pre-soak clock on its MAC. */
    onBeginStaging: (String) -> Boolean,
    /** True when that address is the sensor the pre-soak slot holds. */
    onIsStagingSensor: (String) -> Boolean,
    /** True when that address is the sensor already feeding the loop. */
    onIsProductionSensor: (String) -> Boolean,
    onCancelStaging: () -> Unit,
    onOpenPromote: () -> Unit,
    onStagingDriver: () -> OnePlusCgmDriverReal,
    /**
     * PRODUCTION only: the user started this sensor now → anchor its age and log a sensor change.
     * Called with (MAC of the started sensor, MAC stored before this start).
     */
    onSensorSessionStarted: (String, String?) -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val window = rememberCgmWindow()
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
    /** Set when Connect targets the sensor the pre-soak slot is already running — see the dialog. */
    var slotConflictAddress by remember { mutableStateOf<String?>(null) }

    // Re-seed the whole form from the slot's own store, on first composition and on every slot
    // switch, so what is shown always belongs to the sensor being started.
    //
    // The selection is not seeded here: it follows the code on screen, in the effect below.
    LaunchedEffect(slot) {
        applicatorInput = storedSession?.identity?.rawGs1 ?: storedSession?.identity?.pin.orEmpty()
        parsedIdentity = storedSession?.identity ?: OnePlusGs1ApplicatorParser.parse(applicatorInput)
        selected = null
        deviceChosenByUser = false
        devices.clear()
        errorText = null
        newSensorConfirmPending = false
    }

    // Rank this scan from the identity ON SCREEN, not from the store. As soon as the code belongs to
    // another sensor, the stored MAC and ADV name lose their boost — see OnePlusAdvCandidate.
    val scanHint = remember(storedSession, parsedIdentity) {
        OnePlusAdvCandidate.scanHintFor(storedSession, parsedIdentity)
    }
    // The selection follows the code: the stored sensor stays pre-selected while its own code is on
    // screen (a reconnect needs no choosing), and the moment the code becomes another sensor's the
    // pre-selection goes, because from there on only the user knows which transmitter is theirs.
    LaunchedEffect(scanHint) {
        scanner.sessionHint = scanHint
        if (!deviceChosenByUser) {
            selected = OnePlusBleScannerAndroid.autoSelect(devices, scanHint)
        }
    }

    val codeRequired = stringResource(R.string.dexcom_oneplus_pairing_code_required)
    val codeInvalid = stringResource(R.string.dexcom_oneplus_pairing_code_invalid)
    val permissionsNeeded = stringResource(R.string.dexcom_oneplus_permissions_needed)
    val deviceRequired = stringResource(R.string.dexcom_oneplus_device_required)
    val realSkeletonRequired = stringResource(R.string.dexcom_oneplus_real_skeleton_required)
    val serialNone = stringResource(R.string.dexcom_oneplus_applicator_serial_none)
    val newSensorCodeWarning = stringResource(R.string.dexcom_oneplus_new_sensor_code_warning)
    val stagingIsProduction = stringResource(R.string.dexcom_oneplus_staging_is_production)
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
        //
        // Only a sighting that was really heard is handed over. The pre-selection may be a stored
        // entry the scan never confirmed; passing that pretends an advertisement arrived, which both
        // misses the driver's fast path anyway and talks it out of requiring an ADV. null is the
        // honest answer, and the driver then scans for the sensor itself.
        val sighting = devices.firstOrNull { it.address == address }
            ?: selected?.takeIf { it.seenElapsedMs > 0L }
        if (slot == SensorSlot.STAGING) {
            // Staging: collect-only. Never write the production store, never activate the
            // plugin, never switch the AAPS active BG source — just drive scan/connect on
            // the dedicated staging driver and return to the dashboard.
            //
            // Soaking the sensor that already feeds the loop would put two KEKS handshakes on one
            // link and break both. The plugin refuses it too; this is the message the user reads.
            if (!onBeginStaging(address)) {
                errorText = stagingIsProduction
                return@onConnectClick
            }
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
        // Production start on the sensor that is soaking in the other slot: the same collision, from
        // the other side. Ask rather than act — cancelling the pre-soak silently would throw away a
        // soak clock that is often many hours old, and promoting is usually what the user wants.
        if (onIsStagingSensor(address)) {
            slotConflictAddress = address
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

    val onStartScan: () -> Unit = onStartScan@{
        if (activity != null && !OnePlusBlePermissionHelper.hasAll(activity)) {
            errorText = permissionsNeeded
            OnePlusBlePermissionHelper.requestMissing(activity)
            return@onStartScan
        }
        devices.clear()
        deviceChosenByUser = false
        scanner.sessionHint = scanHint
        selected = OnePlusBleScannerAndroid.autoSelect(devices, scanHint)
        scanner.startScan { hit ->
            mainHandler.post {
                val idx = devices.indexOfFirst { it.address == hit.address }
                if (idx >= 0) devices[idx] = hit else devices.add(hit)
                // The live hint, not the one captured when this lambda was built: the user may type
                // another code while the scan runs. One sensor in range may be selected for them,
                // two or more may not. This is an assignment on purpose — when a second sensor turns
                // up, the single-hit suggestion has to go, and Connect goes with it.
                if (!deviceChosenByUser) {
                    selected = OnePlusBleScannerAndroid.autoSelect(devices, scanner.sessionHint)
                }
            }
        }
        scanning = true
        errorText = null
    }

    val onStopScan: () -> Unit = {
        scanner.stopScan()
        scanning = false
    }

    val onDeviceClick: (OnePlusScanResult) -> Unit = { device ->
        selected = device
        deviceChosenByUser = true
        errorText = null
        newSensorConfirmPending = false
    }

    val rankedDevices = devices.sortedByDescending {
        OnePlusAdvCandidate.rankScore(it.name, it.address, it.rssi, scanHint)
    }
    val connectAction: @Composable ColumnScope.() -> Unit = {
        ConnectAction(
            slot = slot,
            errorText = errorText,
            selectedLabel = selected?.name ?: selected?.address,
            enabled = hasDevice,
            onConnectClick = onConnectClick,
        )
    }

    CgmScaffold(
        title = stringResource(R.string.dexcom_oneplus_start_title),
        onNavigate = onBack,
        // A screen the user either commits (Connect) or abandons — see the AapsTopAppBar convention.
        navIcon = CgmNavIcon.Close,
        constrainWidth = !window.isTwoPane,
        bottomBar = {
            // In two panes the action lives at the foot of the form column, next to what it acts on.
            if (!window.isTwoPane) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(AapsSpacing.extraLarge),
                    verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium),
                    content = connectAction,
                )
            }
        },
    ) {
        if (window.isTwoPane) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .clearFocusOnTap(focusManager),
                horizontalArrangement = Arrangement.spacedBy(AapsSpacing.extraLarge),
            ) {
                Column(
                    // Both panes share the width. A fixed 560 dp form would have left the sensor
                    // list about 250 dp at the 840 dp split point — narrower than one row needs.
                    modifier = Modifier
                        .weight(1f)
                        .widthIn(max = AapsSpacing.readableContentMaxWidth)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(AapsSpacing.extraLarge),
                    verticalArrangement = Arrangement.spacedBy(AapsSpacing.large),
                ) {
                    FormPane(
                        window = window,
                        currentStep = currentStep,
                        slot = slot,
                        onSlotChange = { slot = it; errorText = null },
                        activity = activity,
                        applicatorInput = applicatorInput,
                        parsedIdentity = parsedIdentity,
                        errorText = errorText,
                        serialNone = serialNone,
                        storedMac = storedSession?.lastMac,
                        onApplicatorChange = { text ->
                            applicatorInput = text
                            parsedIdentity = OnePlusGs1ApplicatorParser.parse(text)
                            errorText = null
                            newSensorConfirmPending = false
                        },
                    )
                    connectAction()
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(
                            top = AapsSpacing.extraLarge,
                            end = AapsSpacing.extraLarge,
                            bottom = AapsSpacing.extraLarge,
                        ),
                    verticalArrangement = Arrangement.spacedBy(AapsSpacing.large),
                ) {
                    ScanHeader(
                        scanning = scanning,
                        deviceCount = devices.size,
                        onStartScan = onStartScan,
                        onStopScan = onStopScan,
                    )
                    // Its own pane, so the list gets the whole height instead of a fixed 216 dp box.
                    CgmLazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        deviceItems(
                            devices = rankedDevices,
                            selectedAddress = selected?.address,
                            storedMac = storedSession?.lastMac,
                            onDeviceClick = onDeviceClick,
                        )
                    }
                }
            }
        } else {
            CgmLazyColumn(
                modifier = Modifier.clearFocusOnTap(focusManager),
            ) {
                item(key = "form") {
                    Column(verticalArrangement = Arrangement.spacedBy(AapsSpacing.large)) {
                        FormPane(
                            window = window,
                            currentStep = currentStep,
                            slot = slot,
                            onSlotChange = { slot = it; errorText = null },
                            activity = activity,
                            applicatorInput = applicatorInput,
                            parsedIdentity = parsedIdentity,
                            errorText = errorText,
                            serialNone = serialNone,
                            storedMac = storedSession?.lastMac,
                            onApplicatorChange = { text ->
                                applicatorInput = text
                                parsedIdentity = OnePlusGs1ApplicatorParser.parse(text)
                                errorText = null
                                newSensorConfirmPending = false
                            },
                        )
                    }
                }
                item(key = "scanHeader") {
                    ScanHeader(
                        scanning = scanning,
                        deviceCount = devices.size,
                        onStartScan = onStartScan,
                        onStopScan = onStopScan,
                    )
                }
                // The scanned sensors are items of the screen's own list. Wrapping them in a list
                // with its own bounded height was what pinned them to 216 dp on every screen size.
                deviceItems(
                    devices = rankedDevices,
                    selectedAddress = selected?.address,
                    storedMac = storedSession?.lastMac,
                    onDeviceClick = onDeviceClick,
                )
                item(key = "help") {
                    Column(verticalArrangement = Arrangement.spacedBy(AapsSpacing.large)) {
                        HelpSection(slot = slot)
                    }
                }
            }
        }
    }

    slotConflictAddress?.let { conflicting ->
        SlotConflictDialog(
            onPromote = {
                slotConflictAddress = null
                onOpenPromote()
            },
            onCancelStagingAndStart = {
                slotConflictAddress = null
                // The soak is given up knowingly. Connect is not re-run for the user: the store has
                // just changed under it, so they press it again on a screen that now tells the truth.
                onCancelStaging()
                errorText = null
            },
            onDismiss = { slotConflictAddress = null },
        )
    }
}

/**
 * Asked when Connect targets the sensor that is soaking in the pre-soak slot.
 *
 * Promote is the primary action on purpose: it is what the user usually means, and it keeps the soak
 * the sensor has already earned. Cancelling throws that away, so it is the quieter choice, and it
 * says so.
 */
@Composable
private fun SlotConflictDialog(
    onPromote: () -> Unit,
    onCancelStagingAndStart: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dexcom_oneplus_slot_conflict_title)) },
        text = { Text(stringResource(R.string.dexcom_oneplus_slot_conflict_message)) },
        confirmButton = {
            TextButton(onClick = onPromote) {
                Text(stringResource(R.string.dexcom_oneplus_slot_conflict_promote))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.dexcom_oneplus_slot_conflict_dismiss))
                }
                TextButton(onClick = onCancelStagingAndStart) {
                    Text(stringResource(R.string.dexcom_oneplus_slot_conflict_restart))
                }
            }
        },
    )
}

/** The stepper, slot choice, permission prompt, code field and help — everything but the scan. */
@Composable
private fun ColumnScope.FormPane(
    window: CgmWindow,
    currentStep: Int,
    slot: SensorSlot,
    onSlotChange: (SensorSlot) -> Unit,
    activity: Activity?,
    applicatorInput: String,
    parsedIdentity: OnePlusSensorIdentity?,
    errorText: String?,
    serialNone: String,
    storedMac: String?,
    onApplicatorChange: (String) -> Unit,
) {
    CgmStepper(
        currentStep = currentStep,
        labels = listOf(
            stringResource(R.string.dexcom_oneplus_step_prepare),
            stringResource(R.string.dexcom_oneplus_step_code),
            stringResource(R.string.dexcom_oneplus_step_connect),
            stringResource(R.string.dexcom_oneplus_step_warmup),
        ),
        // Four labels never fit across a phone; the compact form shows one at a time instead of
        // truncating all of them.
        compact = window.width == CgmWidth.Compact,
    )

    // A real single-choice control: the selected side carries the selection state, so TalkBack
    // announces a choice instead of two buttons, one of which used to do nothing at all.
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = slot == SensorSlot.PRODUCTION,
            onClick = { onSlotChange(SensorSlot.PRODUCTION) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
        ) {
            Text(stringResource(R.string.dexcom_oneplus_slot_production_short))
        }
        SegmentedButton(
            selected = slot == SensorSlot.STAGING,
            onClick = { onSlotChange(SensorSlot.STAGING) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
        ) {
            Text(stringResource(R.string.dexcom_oneplus_slot_staging_short))
        }
    }

    if (activity != null && !OnePlusBlePermissionHelper.hasAll(activity)) {
        Button(
            onClick = { OnePlusBlePermissionHelper.requestMissing(activity) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.dexcom_oneplus_request_permissions))
        }
    }

    CgmCard {
        CgmCardHeader(stringResource(R.string.dexcom_oneplus_section_pairing_code))
        OutlinedTextField(
            value = applicatorInput,
            onValueChange = onApplicatorChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.dexcom_oneplus_applicator_gs1_label)) },
            supportingText = {
                if (parsedIdentity != null) {
                    Text(
                        stringResource(
                            R.string.dexcom_oneplus_applicator_parsed,
                            parsedIdentity.pin,
                            parsedIdentity.serial ?: serialNone,
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
        storedMac?.let { mac ->
            Text(
                text = stringResource(R.string.dexcom_oneplus_last_mac_hint, mac),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (window.isTwoPane) HelpSection(slot = slot)
}

/**
 * The five help blocks that used to sit permanently open above the first control — about two
 * screens of scrolling before the user could reach Scan. Folded away, but the one that warns about
 * losing the Dexcom app keeps its warning colour while closed.
 */
@Composable
private fun ColumnScope.HelpSection(slot: SensorSlot) {
    CgmHelpCard(title = stringResource(R.string.dexcom_oneplus_help_how_to_start)) {
        Text(
            text = stringResource(R.string.dexcom_oneplus_start_steps),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    CgmHelpCard(
        title = stringResource(R.string.dexcom_oneplus_help_before_pairing),
        tone = CgmCardTone.Warning,
    ) {
        Text(
            text = stringResource(R.string.dexcom_oneplus_dexcom_recovery_warning),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    CgmHelpCard(title = stringResource(R.string.dexcom_oneplus_help_permissions)) {
        Text(
            text = stringResource(R.string.dexcom_oneplus_permissions_hint),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.dexcom_oneplus_scan_hint),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    CgmHelpCard(title = stringResource(R.string.dexcom_oneplus_help_slot)) {
        Text(
            text = stringResource(R.string.dexcom_oneplus_slot_hint),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    // The real-skeleton gate only applies to the PRODUCTION default driver; the staging driver is
    // always the Real skeleton, so the warning is irrelevant when starting in staging.
    if (slot == SensorSlot.PRODUCTION && !OnePlusCgmDrivers.useRealSkeleton) {
        Text(
            text = stringResource(R.string.dexcom_oneplus_real_skeleton_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun ScanHeader(
    scanning: Boolean,
    deviceCount: Int,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium)) {
        CgmCardHeader(stringResource(R.string.dexcom_oneplus_section_sensors_nearby)) {
            CgmStateChip(
                state = if (scanning) CgmUiState.Working else CgmUiState.Waiting,
                label = if (scanning) {
                    stringResource(R.string.dexcom_oneplus_scan_scanning)
                } else {
                    stringResource(R.string.dexcom_oneplus_scan_idle, deviceCount)
                },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AapsSpacing.medium),
        ) {
            OutlinedButton(
                onClick = onStartScan,
                modifier = Modifier.weight(1f),
                enabled = !scanning,
            ) {
                Text(
                    stringResource(
                        if (deviceCount > 0) R.string.dexcom_oneplus_scan_rescan else R.string.dexcom_oneplus_scan_start,
                    ),
                )
            }
            OutlinedButton(
                onClick = onStopScan,
                modifier = Modifier.weight(1f),
                enabled = scanning,
            ) {
                Text(stringResource(R.string.dexcom_oneplus_scan_stop))
            }
        }
        // Why every nearby sensor is listed, and why the app cannot choose for the user.
        Text(
            text = stringResource(R.string.dexcom_oneplus_scan_pick_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Scanned sensors as items of the caller's list — never a bounded list inside another scroll. */
private fun LazyListScope.deviceItems(
    devices: List<OnePlusScanResult>,
    selectedAddress: String?,
    storedMac: String?,
    onDeviceClick: (OnePlusScanResult) -> Unit,
) {
    if (devices.isEmpty()) {
        item(key = "scanEmpty") {
            Text(
                text = stringResource(R.string.dexcom_oneplus_scan_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        items(devices, key = { it.address }) { device ->
            DeviceRow(
                device = device,
                selected = selectedAddress == device.address,
                lastUsed = storedMac?.equals(device.address, ignoreCase = true) == true,
                onClick = { onDeviceClick(device) },
            )
        }
    }
}

@Composable
private fun DeviceRow(
    device: OnePlusScanResult,
    selected: Boolean,
    lastUsed: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(AapsSpacing.large)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AapsSpacing.listRowMinHeight)
            .background(
                color = if (selected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                },
                shape = shape,
            )
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = AapsSpacing.extraLarge, vertical = AapsSpacing.large),
        horizontalArrangement = Arrangement.spacedBy(AapsSpacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.name ?: stringResource(R.string.dexcom_oneplus_scan_unnamed),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = device.address,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (lastUsed) {
                Text(
                    text = stringResource(R.string.dexcom_oneplus_scan_last_used),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        SignalBars(rssi = device.rssi)
    }
}

/**
 * Signal strength as bars.
 *
 * "−67 dBm" is a number the user cannot act on; four bars say "close enough" or "walk nearer"
 * immediately. The dBm value stays in the driver log for anyone debugging a range problem.
 */
@Composable
private fun SignalBars(rssi: Int) {
    val bars = when {
        rssi == 0   -> 0        // stored MAC re-offered without a live sighting
        rssi >= -60 -> 4
        rssi >= -70 -> 3
        rssi >= -80 -> 2
        else        -> 1
    }
    // The bars are decorative; the dBm value they stand for is what gets read out, so nothing is
    // lost for screen reader users.
    val description = stringResource(R.string.dexcom_oneplus_scan_signal, rssi)
    Row(
        modifier = Modifier.semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(AapsSpacing.extraSmall),
        verticalAlignment = Alignment.Bottom,
    ) {
        (1..4).forEach { index ->
            Box(
                modifier = Modifier
                    .width(AapsSpacing.small)
                    .height(AapsSpacing.small * (index + 1))
                    .background(
                        color = if (index <= bars) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = RoundedCornerShape(AapsSpacing.extraSmall),
                    ),
            )
        }
    }
}

@Composable
private fun ColumnScope.ConnectAction(
    slot: SensorSlot,
    errorText: String?,
    selectedLabel: String?,
    enabled: Boolean,
    onConnectClick: () -> Unit,
) {
    errorText?.let { msg ->
        Text(
            text = msg,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    // Connect overwrites the slot's stored MAC, so the screen says which sensor it is about to
    // adopt rather than leaving the user to check the highlighted row.
    Text(
        text = when {
            slot == SensorSlot.STAGING -> stringResource(R.string.dexcom_oneplus_staging_return_note)
            selectedLabel != null      -> stringResource(R.string.dexcom_oneplus_connect_target, selectedLabel)
            else                       -> stringResource(R.string.dexcom_oneplus_start_return_warmup)
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(
        onClick = onConnectClick,
        modifier = Modifier.fillMaxWidth(),
        // Selecting a device is what the button acts on: disabled until there is one, so
        // "nothing happens" is never mistaken for a broken button.
        enabled = enabled,
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
