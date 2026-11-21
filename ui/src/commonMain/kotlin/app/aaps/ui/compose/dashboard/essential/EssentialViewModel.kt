package app.aaps.ui.compose.dashboard.essential

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.interfaces.aps.AimiBoostControl
import app.aaps.core.interfaces.aps.AimiBoostStartResult
import app.aaps.core.interfaces.aps.AimiBoostState
import app.aaps.core.interfaces.aps.AimiBoostUnavailableReason
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventNsClientStatusUpdated
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.ApsResultSource
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/** A short message after a Boost action, shown for a few seconds under the shape. */
sealed interface EssentialMessage {

    data class Started(val minutes: Int) : EssentialMessage

    data class Refused(val reason: AimiBoostUnavailableReason) : EssentialMessage

    data object Cancelled : EssentialMessage
}

/**
 * The Boost side of the Essential skin. It only passes the user's request on: the AIMI plugin checks
 * every guard again and decides ([AimiBoostControl]).
 */
@OptIn(FlowPreview::class)
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
@Stable
@Inject
class EssentialViewModel(
    private val boostControl: AimiBoostControl,
    private val preferences: Preferences,
    private val activePlugin: ActivePlugin,
    private val config: Config,
    private val processedDeviceStatusData: ProcessedDeviceStatusData,
    private val rxBus: RxBus,
) : ViewModel() {

    /**
     * Bumps when a client receives a device status, so the home re-reads [loopTargetMgdl].
     * Stays at 0 on a phone that loops: nothing is collected from the bus there.
     */
    private val _deviceStatusTick = MutableStateFlow(0)
    val deviceStatusTick: StateFlow<Int> = _deviceStatusTick.asStateFlow()

    init {
        if (config.AAPSCLIENT) {
            viewModelScope.launch {
                rxBus.toFlow(EventNsClientStatusUpdated::class).debounce(300).collect {
                    _deviceStatusTick.value = _deviceStatusTick.value + 1
                }
            }
        }
    }

    /**
     * The target the loop really used in its last run, in mg/dL, or null when that run is older than
     * [LOOP_TARGET_MAX_AGE_MS] or there is none. It can differ from the profile target: AIMI lowers it on a
     * rise (70 to 90) and a Boost keeps it at 80 at most, without a temp target.
     *
     * On a client the loop never runs. The master's target is read from the device status, with the same
     * 15 minute bound (`ApsResultSource.CLIENT_MAX_AGE_MS`).
     */
    fun loopTargetMgdl(nowMs: Long): Double? {
        if (config.AAPSCLIENT) return ApsResultSource.targetMgdl(config, processedDeviceStatusData, nowMs) { null }
        val result = activePlugin.activeAPS?.lastAPSResult ?: return null
        if (nowMs - result.date > LOOP_TARGET_MAX_AGE_MS) return null
        return result.targetBG.takeIf { it.isFinite() && it > 0.0 }
    }

    val boost: StateFlow<AimiBoostState> = boostControl.state

    private val _boostPercent = MutableStateFlow(preferences.get(IntKey.OApsAIMIManualBoostPercent))

    /** The strength of the next Boost, in percent. A Boost that runs keeps the strength it started with. */
    val boostPercent: StateFlow<Int> = _boostPercent.asStateFlow()

    /** Moves the strength by [step] percent, kept inside the setting's own range. */
    fun changeBoostPercent(step: Int) {
        val key = IntKey.OApsAIMIManualBoostPercent
        val next = (preferences.get(key) + step).coerceIn(key.min, key.max)
        preferences.put(key, next)
        _boostPercent.value = next
    }

    private val _messages = MutableSharedFlow<EssentialMessage>(extraBufferCapacity = 1)
    val messages: SharedFlow<EssentialMessage> = _messages.asSharedFlow()

    fun startBoost() {
        viewModelScope.launch {
            val message = when (val result = boostControl.start()) {
                is AimiBoostStartResult.Started -> EssentialMessage.Started(result.remainingMinutes)
                is AimiBoostStartResult.Refused -> EssentialMessage.Refused(result.reason)
            }
            _messages.emit(message)
        }
    }

    fun cancelBoost() {
        if (boostControl.cancel()) _messages.tryEmit(EssentialMessage.Cancelled)
    }

    private companion object {

        /** Two missed loop runs: an older target is not shown, it may no longer be the one in use. */
        const val LOOP_TARGET_MAX_AGE_MS = 15L * 60L * 1000L
    }
}
