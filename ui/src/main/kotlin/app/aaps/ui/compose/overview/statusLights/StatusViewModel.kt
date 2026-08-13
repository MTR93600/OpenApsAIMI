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
import app.aaps.core.interfaces.source.CgmSensorStatusProvider
import app.aaps.core.interfaces.source.CgmWarmupProvider
import app.aaps.core.interfaces.source.PromotionRejectReason
import app.aaps.core.interfaces.source.PromotionResult
import app.aaps.core.interfaces.source.StagingState
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.R
import app.aaps.core.ui.compose.StatusLevel
import app.aaps.core.ui.compose.icons.IcCannulaChange
import app.aaps.core.ui.compose.icons.IcCgmInsert
import app.aaps.core.ui.compose.icons.IcGenericCgm
import app.aaps.core.ui.compose.icons.IcPatchPump
import app.aaps.core.ui.compose.icons.IcPumpBattery
import app.aaps.core.ui.compose.icons.IcPumpCartridge
import app.aaps.core.ui.compose.pump.tickerFlow
import app.aaps.ui.R as UiR
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val _uiState = MutableStateFlow(StatusUiState())
    val uiState: StateFlow<StatusUiState> = _uiState.asStateFlow()

    init {
        setupEventListeners()
        refreshState()
    }

    private fun setupEventListeners() {
        rxBus.toFlow(EventInitializationChanged::class.java)
            .onEach { refreshState() }.launchIn(viewModelScope)
        persistenceLayer.observeChanges(TE::class.java)
            .onEach { refreshState() }.launchIn(viewModelScope)
        persistenceLayer.databaseClearedFlow
            .onEach { refreshState() }.launchIn(viewModelScope)
        rxBus.toFlow(EventPumpStatusChanged::class.java)
            .onEach { refreshState() }.launchIn(viewModelScope)
        rxBus.toFlow(EventNsClientStatusUpdated::class.java)
            .onEach { refreshState() }.launchIn(viewModelScope)
        tickerFlow(60_000L)
            .onEach { refreshState() }.launchIn(viewModelScope)
        // A warm-up countdown and a settling second sensor both move by the minute and only exist
        // for a short while. The shared 60 s tick is too slow to feel live, so add a light tick that
        // costs two StateFlow reads and does nothing at all unless a source reports one of them.
        tickerFlow(10_000L)
            .onEach { if (hasTransientCgmStatus()) refreshState() }.launchIn(viewModelScope)
    }

    /** True while the active source reports a warm-up or holds a second (staging) sensor. */
    private fun hasTransientCgmStatus(): Boolean {
        val source = activePlugin.activeBgSource
        val warming = (source as? CgmWarmupProvider)?.warmupStatus?.value?.active == true
        val staging = (source as? CgmSensorStatusProvider)?.stagingState?.value != null &&
            (source as? CgmSensorStatusProvider)?.stagingState?.value != StagingState.ABSENT
        return warming || staging
    }

    fun refreshState() {
        viewModelScope.launch {
            val pump = activePlugin.activePump
            val pumpDescription = pump.pumpDescription
            val isInitialized = pump.isInitialized()
            val isPatchPump = pumpDescription.isPatchPump

            // Build status items (without expensive TDD calculation)
            val sensorStatus = buildSensorStatus()
            val warmUpStatus = buildWarmUpStatus()
            val secondSensorStatus = buildSecondSensorStatus()
            val insulinStatus = buildInsulinStatus(isPatchPump, pumpDescription.maxReservoirReading.toDouble())
            val cannulaStatus = buildCannulaStatus(isPatchPump, includeTddCalculation = false)
            val batteryStatus = if (!isPatchPump || pumpDescription.useHardwareLink) {
                buildBatteryStatus()
            } else null

            _uiState.update { state ->
                state.copy(
                    sensorStatus = sensorStatus,
                    warmUpStatus = warmUpStatus,
                    secondSensorStatus = secondSensorStatus,
                    canPromoteSecondSensor = secondSensorStatus != null && canPromoteSecondSensor(),
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

            // Calculate cannula usage in background (expensive operation)
            viewModelScope.launch {
                val cannulaStatusWithUsage = buildCannulaStatus(isPatchPump, includeTddCalculation = true)
                _uiState.update { state ->
                    state.copy(cannulaStatus = cannulaStatusWithUsage)
                }
            }
        }
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
            compactLevel = false // Overview: sensor battery not shown
        )
    }

    /**
     * Countdown while the CGM warms up, for any source that reports it. Null for every other source
     * and as soon as the warm-up ends, so the row simply disappears.
     *
     * The remaining time is taken from the wall-clock end time when the protocol gave one, because a
     * stored countdown goes stale between two radio windows.
     *
     * Internal so [StatusViewModelTest] can check the countdown without driving a whole refresh.
     */
    internal fun buildWarmUpStatus(): StatusItem? {
        val status = (activePlugin.activeBgSource as? CgmWarmupProvider)?.warmupStatus?.value ?: return null
        if (!status.active) return null

        val remainingMs = status.endsAtEpochMs?.let { (it - dateUtil.now()).coerceAtLeast(0L) } ?: status.remainingMs
        val age = remainingMs
            ?.let { rh.gs(R.string.format_mins, TimeUnit.MILLISECONDS.toMinutes(it + MINUTE_ROUND_UP_MS).toInt()) }
            ?: rh.gs(UiR.string.overview_cgm_warmup_waiting)
        // Fraction of the warm-up already done, so the bar fills up as the sensor gets ready.
        val total = status.totalMs
        val percent = if (total != null && total > 0L && remainingMs != null) {
            ((total - remainingMs).toFloat() / total).coerceIn(0f, 1f)
        } else -1f

        return StatusItem(
            label = rh.gs(UiR.string.overview_cgm_warmup_label),
            age = age,
            ageStatus = StatusLevel.UNSPECIFIED,
            agePercent = percent,
            icon = IcCgmInsert,
            compactLevel = false
        )
    }

    /**
     * Second sensor warming up next to the one in use, for any source that supports the overlap.
     * Shows how far the settling has gone and how many readings prove the sensor is really alive.
     *
     * Internal so [StatusViewModelTest] can check each staging state without driving a whole refresh.
     */
    internal fun buildSecondSensorStatus(): StatusItem? {
        val source = activePlugin.activeBgSource as? CgmSensorStatusProvider ?: return null
        val state = source.stagingState.value
        if (state == StagingState.ABSENT) return null

        val evidence = source.stagingEvidence.value
        // The source owns the settle rule and publishes what is left of it.
        val settleRemainingMs = source.stagingSettleRemainingMs.value
        val age = when (state) {
            StagingState.WARMUP   -> rh.gs(UiR.string.overview_second_sensor_warmup)
            StagingState.READY    -> rh.gs(UiR.string.overview_second_sensor_ready)
            StagingState.SETTLING ->
                settleRemainingMs
                    // Ceil to whole hours so it reads "ready in 1 h" until the last minutes, never "0 h".
                    ?.let { rh.gs(UiR.string.overview_second_sensor_ready_in, ((it + HOUR_MS - 1L) / HOUR_MS).toInt()) }
                    ?: rh.gs(UiR.string.overview_second_sensor_ready)

            StagingState.ABSENT   -> return null
        }
        val ageMs = source.stagingLifecycle.value?.ageMs
        val percent = if (state == StagingState.SETTLING && settleRemainingMs != null && ageMs != null) {
            (ageMs.toFloat() / (ageMs + settleRemainingMs)).coerceIn(0f, 1f)
        } else -1f

        return StatusItem(
            label = rh.gs(UiR.string.overview_second_sensor_label),
            age = age,
            ageStatus = if (state == StagingState.READY) StatusLevel.NORMAL else StatusLevel.UNSPECIFIED,
            agePercent = percent,
            level = evidence?.let { rh.gs(UiR.string.overview_second_sensor_readings, it.validCount) },
            icon = IcGenericCgm,
            compactLevel = false
        )
    }

    /**
     * The second sensor has settled and may now feed the loop.
     *
     * Internal so [StatusViewModelTest] can check it for every staging state.
     */
    internal fun canPromoteSecondSensor(): Boolean =
        (activePlugin.activeBgSource as? CgmSensorStatusProvider)?.stagingState?.value == StagingState.READY

    /**
     * Hand the loop over to the second sensor. This is the only action here that changes where
     * glucose comes from, so the source re-checks its own gates and may still refuse; either way the
     * outcome is reported back through [StatusUiState.promotionMessage].
     *
     * A refused attempt changes nothing, so there is no rollback to do.
     */
    fun promoteSecondSensor() {
        val source = activePlugin.activeBgSource as? CgmSensorStatusProvider ?: return
        viewModelScope.launch {
            val message = promotionMessage(source.promoteStagingToProduction())
            _uiState.update { it.copy(promotionMessage = message) }
            refreshState()
        }
    }

    /**
     * What to tell the user after a switch attempt: done, or the reason it was refused.
     *
     * Internal so [StatusViewModelTest] can check every reason has its own wording — a refusal the
     * user cannot explain is worse than no button at all.
     */
    internal fun promotionMessage(result: PromotionResult): String = when (result) {
        is PromotionResult.Ok       -> rh.gs(UiR.string.overview_second_sensor_promote_ok)
        is PromotionResult.Rejected -> rh.gs(rejectionMessage(result.reason))
    }

    /** Drop the last switch result once the user has read it. */
    fun clearPromotionMessage() {
        _uiState.update { it.copy(promotionMessage = null) }
    }

    private fun rejectionMessage(reason: PromotionRejectReason): Int = when (reason) {
        PromotionRejectReason.STAGING_ABSENT            -> UiR.string.overview_second_sensor_promote_rejected_absent
        PromotionRejectReason.STAGING_NOT_SETTLED       -> UiR.string.overview_second_sensor_promote_rejected_not_settled
        PromotionRejectReason.STAGING_NO_VALID_GLUCOSE  -> UiR.string.overview_second_sensor_promote_rejected_no_glucose
        PromotionRejectReason.STAGING_NO_RECENT_GLUCOSE -> UiR.string.overview_second_sensor_promote_rejected_no_recent_glucose
        PromotionRejectReason.LOOP_BUSY                 -> UiR.string.overview_second_sensor_promote_rejected_loop_busy
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

    companion object {

        private const val HOUR_MS = 60L * 60L * 1000L

        /** Added before truncating to minutes, so a countdown reads "1 min" until it really hits zero. */
        private const val MINUTE_ROUND_UP_MS = 59_999L
    }
}