package app.aaps.ui.compose.dashboard.essential

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.interfaces.aps.AimiBoostControl
import app.aaps.core.interfaces.aps.AimiBoostStartResult
import app.aaps.core.interfaces.aps.AimiBoostState
import app.aaps.core.interfaces.aps.AimiBoostUnavailableReason
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
@Stable
@Inject
class EssentialViewModel(
    private val boostControl: AimiBoostControl,
) : ViewModel() {

    val boost: StateFlow<AimiBoostState> = boostControl.state

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
}
