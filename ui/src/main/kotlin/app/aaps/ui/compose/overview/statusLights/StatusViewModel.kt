package app.aaps.ui.compose.overview.statusLights

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.data.model.TE
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventInitializationChanged
import app.aaps.core.interfaces.rx.events.EventNsClientStatusUpdated
import app.aaps.core.interfaces.rx.events.EventPumpStatusChanged
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.R
import app.aaps.core.ui.compose.StatusLevel
import app.aaps.core.ui.compose.icons.IcCannulaChange
import app.aaps.core.ui.compose.icons.IcCgmInsert
import app.aaps.core.ui.compose.icons.IcPatchPump
import app.aaps.core.ui.compose.icons.IcPumpBattery
import app.aaps.core.ui.compose.icons.IcPumpCartridge
import app.aaps.core.ui.compose.pump.tickerFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltViewModel
@Stable
class StatusViewModel @Inject constructor(
    private val rh: ResourceHelper,
    private val activePlugin: ActivePlugin,
    private val profileFunction: ProfileFunction,
    private val config: Config,
    private val persistenceLayer: PersistenceLayer,
    private val dateUtil: DateUtil,
    private val rxBus: RxBus,
    private val preferences: Preferences,
    private val tddCalculator: TddCalculator,
    private val decimalFormatter: DecimalFormatter,
    private val processedDeviceStatusData: ProcessedDeviceStatusData
) : ViewModel() {
    private enum class PerformanceProfile(
        val coalesceDebounceMs: Long,
        val nsSourceDebounceMs: Long,
        val tickMinIntervalMs: Long,
    ) {
        BALANCED(
            coalesceDebounceMs = 100L,
            nsSourceDebounceMs = 300L,
            tickMinIntervalMs = 60_000L,
        ),
        SAFE(
            coalesceDebounceMs = 220L,
            nsSourceDebounceMs = 450L,
            tickMinIntervalMs = 180_000L,
        ),
    }

    private enum class RefreshReason {
        Initialization,
        TherapyChange,
        PumpStatus,
        NsClientStatus,
        Tick,
        Manual,
    }

    private companion object {
        private val CANNULA_USAGE_REASONS = setOf(
            RefreshReason.Initialization,
            RefreshReason.TherapyChange,
            RefreshReason.Manual,
        )
    }

    private val _uiState = MutableStateFlow(StatusUiState())
    val uiState: StateFlow<StatusUiState> = _uiState.asStateFlow()
    private val pendingReasons = MutableStateFlow<Set<RefreshReason>>(emptySet())
    private val performanceProfile = MutableStateFlow(PerformanceProfile.BALANCED)
    private var cannulaUsageJob: Job? = null
    private var lastTickRefreshAtMs: Long = 0L

    init {
        observePerformanceProfile()
        observeRefreshRequests()
        setupEventListeners()
        requestRefresh(RefreshReason.Initialization)
    }

    private fun observePerformanceProfile() {
        preferences.observe(BooleanKey.GeneralLowEndStabilityMode)
            .onEach { enabled ->
                performanceProfile.value = if (enabled) PerformanceProfile.SAFE else PerformanceProfile.BALANCED
            }
            .launchIn(viewModelScope)
    }

    private fun setupEventListeners() {
        rxBus.toFlow(EventInitializationChanged::class.java)
            .onEach { requestRefresh(RefreshReason.Initialization) }.launchIn(viewModelScope)
        persistenceLayer.observeChanges(TE::class.java)
            .onEach { requestRefresh(RefreshReason.TherapyChange) }.launchIn(viewModelScope)
        persistenceLayer.databaseClearedFlow
            .onEach { requestRefresh(RefreshReason.Manual) }.launchIn(viewModelScope)
        rxBus.toFlow(EventPumpStatusChanged::class.java)
            .onEach { requestRefresh(RefreshReason.PumpStatus) }.launchIn(viewModelScope)
        performanceProfile
            .flatMapLatest { profile ->
                rxBus.toFlow(EventNsClientStatusUpdated::class.java)
                    .debounce(profile.nsSourceDebounceMs)
            }
            .onEach { requestRefresh(RefreshReason.NsClientStatus) }.launchIn(viewModelScope)
        tickerFlow(60_000L)
            .onEach { requestRefresh(RefreshReason.Tick) }.launchIn(viewModelScope)
    }

    fun refreshState() {
        requestRefresh(RefreshReason.Manual)
    }

    private fun requestRefresh(reason: RefreshReason) {
        pendingReasons.update { it + reason }
    }

    private fun observeRefreshRequests() {
        viewModelScope.launch {
            performanceProfile
                .flatMapLatest { profile ->
                    pendingReasons
                        .filter { it.isNotEmpty() }
                        .debounce(profile.coalesceDebounceMs)
                }
                .collectLatest { reasons ->
                    pendingReasons.update { current -> current - reasons }
                    refreshStateInternal(reasons)
                }
        }
    }

    private suspend fun refreshStateInternal(reasons: Set<RefreshReason>) {
            val profile = performanceProfile.value
            val now = dateUtil.now()
            val tickOnly = reasons == setOf(RefreshReason.Tick)
            if (tickOnly && (now - lastTickRefreshAtMs) < profile.tickMinIntervalMs) return
            if (tickOnly) lastTickRefreshAtMs = now

            val pump = activePlugin.activePump
            val pumpDescription = pump.pumpDescription
            val isInitialized = pump.isInitialized()
            val isPatchPump = pumpDescription.isPatchPump

            // NS client status can arrive in bursts; in AAPSCLIENT this event only changes
            // the followed pump payload, so refresh only insulin status in this narrow path.
            if (config.AAPSCLIENT && reasons == setOf(RefreshReason.NsClientStatus)) {
                val insulinStatus = buildInsulinStatus(isPatchPump, pumpDescription.maxReservoirReading.toDouble())
                _uiState.update { state -> state.copy(insulinStatus = insulinStatus) }
                return
            }

            // Build status items (without expensive TDD calculation)
            val sensorStatus = buildSensorStatus()
            val insulinStatus = buildInsulinStatus(isPatchPump, pumpDescription.maxReservoirReading.toDouble())
            val cannulaStatus = buildCannulaStatus(isPatchPump, includeTddCalculation = false)
            val batteryStatus = if (!isPatchPump || pumpDescription.useHardwareLink) {
                buildBatteryStatus()
            } else null

            _uiState.update { state ->
                state.copy(
                    sensorStatus = sensorStatus,
                    insulinStatus = insulinStatus,
                    // Preserve previous cannula level while TDD recalculates
                    cannulaStatus = state.cannulaStatus?.let { prev ->
                        cannulaStatus.copy(
                            level = prev.level,
                            levelStatus = prev.levelStatus,
                            levelPercent = prev.levelPercent
                        )
                    } ?: cannulaStatus,
                    batteryStatus = batteryStatus,
                    showFill = pumpDescription.isRefillingCapable && isInitialized,
                    showPumpBatteryChange = pumpDescription.isBatteryReplaceable || pump.isBatteryChangeLoggingEnabled(),
                    isPatchPump = isPatchPump
                )
            }

            // Calculate cannula usage only when the source data can actually change.
            if (reasons.any { it in CANNULA_USAGE_REASONS }) {
                cannulaUsageJob?.cancel()
                cannulaUsageJob = viewModelScope.launch {
                    val cannulaStatusWithUsage = buildCannulaStatus(isPatchPump, includeTddCalculation = true)
                    _uiState.update { state ->
                        state.copy(cannulaStatus = cannulaStatusWithUsage)
                    }
                }
            }
    }

    override fun onCleared() {
        cannulaUsageJob?.cancel()
        super.onCleared()
    }

    private suspend fun buildSensorStatus(): StatusItem {
        val event = withContext(Dispatchers.IO) {
            persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.SENSOR_CHANGE)
        }
        val bgSource = activePlugin.activeBgSource
        // Sensor battery: not shown in Overview (compact), shown in Actions (expanded) unless AAPSCLIENT
        val hasBattery = !config.AAPSCLIENT && bgSource.sensorBatteryLevel != -1
        val level = if (hasBattery) "${bgSource.sensorBatteryLevel}%" else null
        val levelPercent = if (hasBattery) bgSource.sensorBatteryLevel / 100f else -1f

        return StatusItem(
            label = rh.gs(R.string.sensor_label),
            age = event?.let { formatAge(it.timestamp) } ?: "-",
            ageStatus = event?.let { getAgeStatus(it.timestamp, IntKey.OverviewSageWarning, IntKey.OverviewSageCritical) } ?: StatusLevel.UNSPECIFIED,
            agePercent = event?.let { getAgePercent(it.timestamp, IntKey.OverviewSageCritical) } ?: 0f,
            level = level,
            levelStatus = if (levelPercent >= 0) getLevelStatus((levelPercent * 100).toDouble(), IntKey.OverviewSbatWarning, IntKey.OverviewSbatCritical) else StatusLevel.UNSPECIFIED,
            levelPercent = if (levelPercent >= 0) 1f - levelPercent else -1f,
            icon = IcCgmInsert,
            compactLevel = true // Overview: show sensor battery (Eversense / native CGM)
        )
    }

    private suspend fun buildInsulinStatus(isPatchPump: Boolean, maxReading: Double): StatusItem {
        val event = withContext(Dispatchers.IO) {
            persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.INSULIN_CHANGE)
        }
        // AAPSCLIENT: local activePump is VirtualPump with a stale hardcoded reservoir.
        // The followed pump's real reservoir arrives via NS device status (already in display units).
        val reservoirLevel = if (config.AAPSCLIENT) {
            processedDeviceStatusData.pumpData?.reservoir ?: 0.0
        } else {
            // Concentration comes from the running profile, which owns the authoritative iCfg. With no
            // profile there is no IU conversion to make, so the reservoir reads as unavailable — 0.0
            // takes the existing "-" / UNSPECIFIED branch below rather than showing a mis-scaled figure.
            profileFunction.getProfile()?.let { activePlugin.activePump.reservoirLevel.value.iU(it.insulinConcentration()) } ?: 0.0
        }
        val insulinUnit = rh.gs(R.string.insulin_unit_shortname)

        val level: String? = if (reservoirLevel > 0) {
            if (!config.AAPSCLIENT && isPatchPump && reservoirLevel >= maxReading) {
                "${decimalFormatter.to0Decimal(maxReading)}+ $insulinUnit"
            } else {
                decimalFormatter.to0Decimal(reservoirLevel, insulinUnit)
            }
        } else null

        return StatusItem(
            label = rh.gs(R.string.insulin_label),
            age = event?.let { formatAge(it.timestamp) } ?: "-",
            ageStatus = event?.let { getAgeStatus(it.timestamp, IntKey.OverviewIageWarning, IntKey.OverviewIageCritical) } ?: StatusLevel.UNSPECIFIED,
            agePercent = event?.let { getAgePercent(it.timestamp, IntKey.OverviewIageCritical) } ?: 0f,
            level = level,
            levelStatus = if (reservoirLevel > 0) getLevelStatus(reservoirLevel, IntKey.OverviewResWarning, IntKey.OverviewResCritical) else StatusLevel.UNSPECIFIED,
            levelPercent = -1f, // No progress bar - reservoir sizes vary by pump
            icon = IcPumpCartridge,
            compactAge = !isPatchPump, // Overview: insulin age hidden for patch pumps
        )
    }

    private suspend fun buildCannulaStatus(isPatchPump: Boolean, includeTddCalculation: Boolean = true): StatusItem {
        val event = withContext(Dispatchers.IO) {
            persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.CANNULA_CHANGE)
        }
        val insulinUnit = rh.gs(R.string.insulin_unit_shortname)

        // Calculate usage since last cannula change (expensive - can be deferred)
        val usage = if (includeTddCalculation && event != null) {
            withContext(Dispatchers.IO) {
                tddCalculator.calculateInterval(event.timestamp, dateUtil.now(), allowMissingData = false)?.totalAmount ?: 0.0
            }
        } else 0.0

        val label = if (isPatchPump) rh.gs(R.string.patch_pump) else rh.gs(R.string.cannula)
        val icon = if (isPatchPump) IcPatchPump else IcCannulaChange

        return StatusItem(
            label = label,
            age = event?.let { formatAge(it.timestamp) } ?: "-",
            ageStatus = event?.let { getAgeStatus(it.timestamp, IntKey.OverviewCageWarning, IntKey.OverviewCageCritical) } ?: StatusLevel.UNSPECIFIED,
            agePercent = event?.let { getAgePercent(it.timestamp, IntKey.OverviewCageCritical) } ?: 0f,
            level = if (usage > 0) decimalFormatter.to0Decimal(usage, insulinUnit) else null,
            levelStatus = StatusLevel.UNSPECIFIED, // Usage doesn't have warning thresholds
            levelPercent = -1f,
            icon = icon,
            compactLevel = false // Overview: cannula usage not shown
        )
    }

    private suspend fun buildBatteryStatus(): StatusItem? {
        val pump = activePlugin.activePump
        val hasAge = pump.pumpDescription.isBatteryReplaceable || pump.isBatteryChangeLoggingEnabled()

        // Eros doesn't report battery itself, but RileyLink alternatives may
        val erosBatteryLinkAvailable = pump.model() == PumpType.OMNIPOD_EROS && pump.isUseRileyLinkBatteryLevel()
        val batteryLevelValue = pump.batteryLevel.value?.toDouble()
        val hasLevel = batteryLevelValue != null && (pump.model().supportBatteryLevel || erosBatteryLinkAvailable)

        // If neither age nor level can be shown, skip entirely
        if (!hasAge && !hasLevel) return null

        val event = if (hasAge) withContext(Dispatchers.IO) {
            persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.PUMP_BATTERY_CHANGE)
        } else null

        // AAPSCLIENT: followed pump's battery is shown in the NSClient status card,
        // so suppress it here (no "n/a" placeholder cluttering the pill).
        val showLevel = !config.AAPSCLIENT && hasLevel

        // Overview compact: pbLevel.visibility based on pump model only (Eros OR not Combo/Dash)
        val useBatteryLevel = pump.model() == PumpType.OMNIPOD_EROS
            || (pump.model() != PumpType.ACCU_CHEK_COMBO && pump.model() != PumpType.OMNIPOD_DASH)

        return StatusItem(
            label = rh.gs(R.string.pb_label),
            age = event?.let { formatAge(it.timestamp) } ?: "-",
            ageStatus = event?.let { getAgeStatus(it.timestamp, IntKey.OverviewBageWarning, IntKey.OverviewBageCritical) } ?: StatusLevel.UNSPECIFIED,
            agePercent = event?.let { getAgePercent(it.timestamp, IntKey.OverviewBageCritical) } ?: 0f,
            level = if (showLevel) "${batteryLevelValue.toInt()}%" else null,
            levelStatus = if (showLevel) getLevelStatus(batteryLevelValue, IntKey.OverviewBattWarning, IntKey.OverviewBattCritical) else StatusLevel.UNSPECIFIED,
            levelPercent = if (showLevel) 1f - (batteryLevelValue.toFloat() / 100f) else -1f,
            icon = IcPumpBattery,
            compactAge = hasAge, // Overview: pbAge shown only if replaceable/logging
            compactLevel = showLevel && useBatteryLevel, // hidden when no level value (e.g., AAPSCLIENT)
            expandedLevel = showLevel
        )
    }

    private fun formatAge(timestamp: Long): String {
        val diff = dateUtil.computeDiff(timestamp, System.currentTimeMillis())
        val days = diff[TimeUnit.DAYS] ?: 0
        val hours = diff[TimeUnit.HOURS] ?: 0
        return if (rh.shortTextMode()) {
            "${days}${rh.gs(app.aaps.core.interfaces.R.string.shortday)}${hours}${rh.gs(app.aaps.core.interfaces.R.string.shorthour)}"
        } else {
            "$days ${rh.gs(app.aaps.core.interfaces.R.string.days)} $hours ${rh.gs(app.aaps.core.interfaces.R.string.hours)}"
        }
    }

    private fun getAgeStatus(timestamp: Long, warnKey: IntPreferenceKey, urgentKey: IntPreferenceKey): StatusLevel {
        val warnHours = preferences.get(warnKey)
        val urgentHours = preferences.get(urgentKey)
        val ageHours = (System.currentTimeMillis() - timestamp) / (1000 * 60 * 60)
        return when {
            ageHours >= urgentHours -> StatusLevel.CRITICAL
            ageHours >= warnHours   -> StatusLevel.WARNING
            else                    -> StatusLevel.NORMAL
        }
    }

    private fun getAgePercent(timestamp: Long, urgentKey: IntPreferenceKey): Float {
        val urgentHours = preferences.get(urgentKey)
        if (urgentHours <= 0) return 0f
        val ageHours = (System.currentTimeMillis() - timestamp) / (1000.0 * 60 * 60)
        return (ageHours / urgentHours).coerceIn(0.0, 1.0).toFloat()
    }

    private fun getLevelStatus(level: Double, warnKey: IntKey, criticalKey: IntKey): StatusLevel {
        val warn = preferences.get(warnKey)
        val critical = preferences.get(criticalKey)
        return when {
            level <= critical -> StatusLevel.CRITICAL
            level <= warn     -> StatusLevel.WARNING
            else              -> StatusLevel.NORMAL
        }
    }
}