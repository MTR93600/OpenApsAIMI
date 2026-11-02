package app.aaps.plugins.sync.nsclientV3.remotecontrol

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.aps.AimiRemoteContext
import app.aaps.core.interfaces.concurrent.aapsIoDispatcher
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.nsclient.NSClientRepository
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.ui.CoreUiStrings
import app.aaps.plugins.sync.SyncStrings
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which part of the Remote Control screen is shown. */
enum class RemoteControlGate {

    /** No access password yet: the first person must set one. */
    SET_PASSWORD,

    /** A password is set but the screen is locked. */
    LOGIN,

    /** Unlocked: the commands are shown. */
    UNLOCKED
}

/** A "cancel all" command waiting for the user to confirm it. */
@Immutable
data class PendingStop(val mode: ModePreset, val pin: String)

@Immutable
data class RemoteControlUiState(
    val gate: RemoteControlGate = RemoteControlGate.LOGIN,
    val pinConfigured: Boolean = false,
    val loginFailed: Boolean = false,
    /** The new access password, shown once right after it was set. Null otherwise. */
    val passwordToShare: String? = null,
    val pendingStop: PendingStop? = null,
    val activeModes: List<ActiveModeItem> = emptyList(),
    /** The clock the remaining times on the Active tab are counted from. */
    val now: Long = 0L,
    val llmBusy: Boolean = false
)

/**
 * The AIMI Remote Control screen on the follower phone.
 *
 * Port of `RemoteControlFragment` from dev_OAPSAIMI:
 * - meal and control modes go out as two notes (see [RemoteControlCommands.modeNotes]);
 * - activity and physiological modes, and the AI text, go out as AIMI contexts with the PIN, through
 *   [AimiRemoteContext]. The master applies them only when the PIN is its own AIMI remote PIN.
 *
 * Messages for the user come out of [messages], already translated.
 */
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
@Stable
@Inject
class RemoteControlViewModel(
    private val rh: TextResolver,
    private val aapsLogger: AAPSLogger,
    private val dateUtil: DateUtil,
    private val persistenceLayer: PersistenceLayer,
    private val nsClientRepository: NSClientRepository,
    private val aimiRemoteContext: AimiRemoteContext,
    private val access: RemoteControlAccess
) : ViewModel() {

    private val _uiState = MutableStateFlow(RemoteControlUiState())
    val uiState: StateFlow<RemoteControlUiState> = _uiState.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        updateGate()
    }

    private fun currentGate(): RemoteControlGate = when {
        !access.isPasswordConfigured() -> RemoteControlGate.SET_PASSWORD
        !access.isUnlocked()           -> RemoteControlGate.LOGIN
        else                           -> RemoteControlGate.UNLOCKED
    }

    /** Read the lock state again. A session that ran out while the screen was open locks it. */
    fun updateGate() {
        _uiState.update { it.copy(gate = currentGate(), pinConfigured = access.isPinConfigured()) }
    }

    private fun message(text: String) {
        _messages.tryEmit(text)
    }

    // --- Access password ---

    fun setupPassword(password: String, confirm: String) {
        when {
            password.isEmpty()                                -> message(rh.gs(SyncStrings.aimi_rc_password_required))
            !RemoteControlAccess.isPasswordValid(password)    -> message(rh.gs(SyncStrings.aimi_rc_password_min_length))
            password != confirm                               -> message(rh.gs(SyncStrings.aimi_rc_passwords_do_not_match))
            else                                              -> {
                access.configurePassword(password)
                message(rh.gs(SyncStrings.aimi_rc_password_configured))
                _uiState.update { it.copy(passwordToShare = password) }
                updateGate()
            }
        }
    }

    fun passwordShown() {
        _uiState.update { it.copy(passwordToShare = null) }
    }

    fun login(password: String) {
        if (password.isEmpty()) {
            message(rh.gs(SyncStrings.aimi_rc_password_required))
            return
        }
        val valid = access.verifyPassword(password)
        _uiState.update { it.copy(loginFailed = !valid) }
        updateGate()
        if (valid) refreshActiveModes()
    }

    fun lock() {
        access.lock()
        updateGate()
    }

    // --- PIN ---

    fun savePin(newPin: String, confirm: String) {
        when {
            newPin.isEmpty()                                -> message(rh.gs(SyncStrings.aimi_rc_pin_empty))
            newPin.length < RemoteControlAccess.MIN_PIN_LENGTH -> message(rh.gs(SyncStrings.aimi_rc_pin_too_short))
            newPin != confirm                               -> message(rh.gs(SyncStrings.aimi_rc_pins_do_not_match))
            else                                            -> {
                access.savePin(newPin)
                message(rh.gs(SyncStrings.aimi_rc_pin_saved))
                updateGate()
            }
        }
    }

    // --- Commands ---

    /** Send [mode] after the PIN check. "Cancel all" waits for [confirmStop] first. */
    fun requestSend(mode: ModePreset, durationText: String, pin: String) {
        if (!access.validatePin(pin)) {
            message(rh.gs(SyncStrings.aimi_rc_invalid_pin))
            return
        }
        if (mode.id == ModePresets.STOP_ID) {
            _uiState.update { it.copy(pendingStop = PendingStop(mode, pin)) }
            return
        }
        val duration = durationText.trim().toIntOrNull()?.takeIf { it > 0 } ?: mode.defaultDurationMin
        send(mode, duration, pin)
    }

    fun confirmStop() {
        val pending = _uiState.value.pendingStop ?: return
        _uiState.update { it.copy(pendingStop = null) }
        send(pending.mode, 0, pending.pin)
    }

    fun dismissStop() {
        _uiState.update { it.copy(pendingStop = null) }
    }

    private fun send(mode: ModePreset, durationMin: Int, pin: String) {
        val sentText = rh.gs(SyncStrings.aimi_rc_mode_sent, rh.gs(mode.name), rh.gs(CoreUiStrings.format_mins, durationMin))
        viewModelScope.launch {
            try {
                if (mode.category.sentAsNote) {
                    val notes = RemoteControlCommands.modeNotes(mode, durationMin, pin, dateUtil.now())
                    aapsLogger.info(LTag.NSCLIENT, "[RemoteControl] Sending ${mode.id} as notes, $durationMin min")
                    withContext(aapsIoDispatcher) { notes.forEach { persistenceLayer.insertOrUpdateTherapyEvent(it) } }
                } else {
                    val text = RemoteControlCommands.contextText(mode, durationMin)
                    aapsLogger.info(LTag.NSCLIENT, "[RemoteControl] Sending ${mode.id} as AIMI context: '$text'")
                    val ids = withContext(aapsIoDispatcher) { aimiRemoteContext.addFromText(text, useLlm = false, remotePin = pin) }
                    if (ids.isEmpty()) {
                        aapsLogger.error(LTag.NSCLIENT, "[RemoteControl] No context parsed for '$text'")
                        message(rh.gs(SyncStrings.aimi_rc_context_not_understood))
                        return@launch
                    }
                }
                nsClientRepository.addLog("► REMOTE", sentText)
                message(sentText)
                refreshActiveModes()
            } catch (e: Exception) {
                aapsLogger.error(LTag.NSCLIENT, "[RemoteControl] Failed to send ${mode.id}", e)
                message(rh.gs(SyncStrings.aimi_rc_send_failed, e.message ?: ""))
            }
        }
    }

    /**
     * Send free text to the AI context parser.
     *
     * Dev sent this without the PIN, so the master always refused it. Here the PIN is checked and
     * sent like for the other contexts.
     */
    fun sendLlm(text: String, pin: String) {
        if (text.isBlank()) {
            message(rh.gs(SyncStrings.aimi_rc_text_empty))
            return
        }
        if (!access.validatePin(pin)) {
            message(rh.gs(SyncStrings.aimi_rc_invalid_pin))
            return
        }
        _uiState.update { it.copy(llmBusy = true) }
        viewModelScope.launch {
            try {
                val ids = withContext(aapsIoDispatcher) { aimiRemoteContext.addFromText(text, useLlm = true, remotePin = pin) }
                if (ids.isNotEmpty()) {
                    aapsLogger.info(LTag.NSCLIENT, "[RemoteControl] AI context created: $ids")
                    message(rh.gs(SyncStrings.aimi_rc_llm_success))
                    refreshActiveModes()
                } else {
                    message(rh.gs(SyncStrings.aimi_rc_llm_nothing))
                }
            } catch (e: Exception) {
                aapsLogger.error(LTag.NSCLIENT, "[RemoteControl] AI context failed", e)
                message(rh.gs(SyncStrings.aimi_rc_llm_error, e.message ?: ""))
            } finally {
                _uiState.update { it.copy(llmBusy = false) }
            }
        }
    }

    /** Read the notes of the last day again and show what is still running. */
    fun refreshActiveModes() {
        updateGate()
        if (_uiState.value.gate != RemoteControlGate.UNLOCKED) return
        viewModelScope.launch {
            try {
                val now = dateUtil.now()
                val events = withContext(aapsIoDispatcher) { persistenceLayer.getTherapyEventDataFromTime(now - T.days(1).msecs(), true) }
                _uiState.update { it.copy(activeModes = RemoteControlCommands.activeModes(events, now), now = now) }
            } catch (e: Exception) {
                aapsLogger.error(LTag.NSCLIENT, "[RemoteControl] Failed to refresh active modes", e)
            }
        }
    }
}
