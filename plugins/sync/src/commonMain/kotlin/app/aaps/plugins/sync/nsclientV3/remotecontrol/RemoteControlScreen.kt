package app.aaps.plugins.sync.nsclientV3.remotecontrol

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.clearFocusOnTap
import app.aaps.core.ui.compose.dialogs.OkCancelDialog
import app.aaps.core.ui.compose.dialogs.OkDialog
import app.aaps.core.ui.compose.stringResource
import app.aaps.plugins.sync.SyncStrings

/** The whole Remote Control screen, below the toolbar. */
@Composable
fun RemoteControlScreenContent(
    state: RemoteControlUiState,
    onSetupPassword: (password: String, confirm: String) -> Unit,
    onLogin: (password: String) -> Unit,
    onPasswordShown: () -> Unit,
    onSavePin: (pin: String, confirm: String) -> Unit,
    onSendMode: (mode: ModePreset, durationText: String, pin: String) -> Unit,
    onConfirmStop: () -> Unit,
    onDismissStop: () -> Unit,
    onSendLlm: (text: String, pin: String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    state.passwordToShare?.let { password ->
        OkDialog(
            title = stringResource(SyncStrings.aimi_rc_password_share_title),
            message = stringResource(SyncStrings.aimi_rc_password_share_message, password),
            onDismiss = onPasswordShown
        )
    }
    if (state.pendingStop != null) {
        OkCancelDialog(
            title = stringResource(SyncStrings.aimi_rc_title),
            message = stringResource(SyncStrings.aimi_rc_cancel_all_confirm),
            onConfirm = onConfirmStop,
            onDismiss = onDismissStop
        )
    }

    val focusManager = LocalFocusManager.current
    Box(modifier = modifier.fillMaxSize().clearFocusOnTap(focusManager)) {
        when (state.gate) {
            RemoteControlGate.SET_PASSWORD -> PasswordSetupCard(onSetupPassword)
            RemoteControlGate.LOGIN        -> LoginCard(state.loginFailed, onLogin)
            RemoteControlGate.UNLOCKED     -> CommandsContent(state, onSavePin, onSendMode, onSendLlm, onRefresh)
        }
    }
}

@Composable
private fun PasswordField(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun PinField(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun GateCard(title: String, message: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(AapsSpacing.extraLarge),
        verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium)
    ) {
        Text(text = title, style = MaterialTheme.typography.titleLarge)
        Text(text = message, style = MaterialTheme.typography.bodyMedium)
        content()
    }
}

@Composable
private fun PasswordSetupCard(onSetupPassword: (String, String) -> Unit) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    GateCard(
        title = stringResource(SyncStrings.aimi_rc_password_setup_title),
        message = stringResource(SyncStrings.aimi_rc_password_setup_message)
    ) {
        PasswordField(password, { password = it }, stringResource(SyncStrings.aimi_rc_password_label))
        PasswordField(confirm, { confirm = it }, stringResource(SyncStrings.aimi_rc_password_confirm_label))
        Button(onClick = { onSetupPassword(password, confirm) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(CoreUiStrings.save))
        }
    }
}

@Composable
private fun LoginCard(loginFailed: Boolean, onLogin: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    GateCard(
        title = stringResource(SyncStrings.aimi_rc_login_title),
        message = stringResource(SyncStrings.aimi_rc_login_message)
    ) {
        PasswordField(password, { password = it }, stringResource(SyncStrings.aimi_rc_password_label))
        if (loginFailed) {
            Text(
                text = stringResource(SyncStrings.aimi_rc_invalid_password),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Button(
            onClick = {
                onLogin(password)
                password = ""
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(SyncStrings.aimi_rc_unlock))
        }
    }
}

@Composable
private fun CommandsContent(
    state: RemoteControlUiState,
    onSavePin: (String, String) -> Unit,
    onSendMode: (ModePreset, String, String) -> Unit,
    onSendLlm: (String, String) -> Unit,
    onRefresh: () -> Unit
) {
    // The PIN is typed again for each visit of the screen, as on dev; it is not kept.
    var pin by remember { mutableStateOf("") }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(horizontal = AapsSpacing.extraLarge, vertical = AapsSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium)
        ) {
            if (!state.pinConfigured) PinSetupCard(onSavePin)
            Text(text = stringResource(SyncStrings.aimi_rc_pin_instruction), style = MaterialTheme.typography.bodyMedium)
            PinField(pin, { pin = it }, stringResource(SyncStrings.aimi_rc_enter_pin))
        }
        val tabs = listOf(
            SyncStrings.aimi_rc_tab_modes,
            SyncStrings.aimi_rc_tab_contexts,
            SyncStrings.aimi_rc_tab_active,
            SyncStrings.aimi_rc_tab_llm
        )
        PrimaryTabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = {
                        selectedTab = index
                        if (index == TAB_ACTIVE) onRefresh()
                    },
                    text = { Text(stringResource(title)) }
                )
            }
        }
        when (selectedTab) {
            TAB_MODES    -> ModeList(ModePresets.MODES_TAB) { mode, duration -> onSendMode(mode, duration, pin) }
            TAB_CONTEXTS -> ModeList(ModePresets.CONTEXTS_TAB) { mode, duration -> onSendMode(mode, duration, pin) }
            TAB_ACTIVE   -> ActiveModeList(state.activeModes, state.now, onRefresh)
            else         -> LlmTab(state.llmBusy) { text -> onSendLlm(text, pin) }
        }
    }
}

private const val TAB_MODES = 0
private const val TAB_CONTEXTS = 1
private const val TAB_ACTIVE = 2

@Composable
private fun PinSetupCard(onSavePin: (String, String) -> Unit) {
    var newPin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(AapsSpacing.extraLarge),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium)
        ) {
            Text(text = stringResource(SyncStrings.aimi_rc_pin_setup_title), style = MaterialTheme.typography.titleMedium)
            Text(text = stringResource(SyncStrings.aimi_rc_pin_setup_message), style = MaterialTheme.typography.bodyMedium)
            PinField(newPin, { newPin = it }, stringResource(SyncStrings.aimi_rc_new_pin_label))
            PinField(confirm, { confirm = it }, stringResource(SyncStrings.aimi_rc_confirm_pin_label))
            Button(onClick = { onSavePin(newPin, confirm) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(SyncStrings.aimi_rc_save_pin))
            }
        }
    }
}

private fun ModeCategory.icon(): ImageVector = when (this) {
    ModeCategory.MEAL                                -> Icons.Default.Restaurant
    ModeCategory.ACTIVITY, ModeCategory.CONTEXT_ONLY -> Icons.AutoMirrored.Filled.DirectionsRun
    ModeCategory.PHYSIO                              -> Icons.Default.Favorite
    ModeCategory.CONTROL                             -> Icons.Default.Block
}

@Composable
private fun ModeList(modes: List<ModePreset>, onSend: (ModePreset, String) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium)
    ) {
        items(modes, key = { it.id }) { mode -> ModeRow(mode, onSend) }
    }
}

@Composable
private fun ModeRow(mode: ModePreset, onSend: (ModePreset, String) -> Unit) {
    var duration by rememberSaveable(mode.id) { mutableStateOf(mode.defaultDurationMin.toString()) }
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = AapsSpacing.extraLarge)) {
        Row(
            modifier = Modifier.padding(AapsSpacing.large),
            horizontalArrangement = Arrangement.spacedBy(AapsSpacing.medium),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(imageVector = mode.category.icon(), contentDescription = null)
            Column(modifier = Modifier.weight(1f)) {
                Text(text = stringResource(mode.name), style = MaterialTheme.typography.titleMedium)
                Text(text = stringResource(mode.description), style = MaterialTheme.typography.bodySmall)
                if (mode.id != ModePresets.STOP_ID) {
                    OutlinedTextField(
                        value = duration,
                        onValueChange = { text -> duration = text.filter { it.isDigit() } },
                        label = { Text(stringResource(SyncStrings.aimi_rc_duration_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done)
                    )
                }
            }
            Button(onClick = { onSend(mode, duration) }) {
                Text(stringResource(SyncStrings.aimi_rc_send))
            }
        }
    }
}

@Composable
private fun ActiveModeList(activeModes: List<ActiveModeItem>, now: Long, onRefresh: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(AapsSpacing.extraLarge),
        verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium)
    ) {
        OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(CoreUiStrings.refresh))
        }
        if (activeModes.isEmpty()) {
            Text(text = stringResource(SyncStrings.aimi_rc_no_active_modes), style = MaterialTheme.typography.bodyMedium)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium)) {
            items(activeModes, key = { it.mode.id }) { item -> ActiveModeRow(item, now) }
        }
    }
}

@Composable
private fun ActiveModeRow(item: ActiveModeItem, now: Long) {
    val minutes = item.remainingMinutes(now)
    val remaining = when {
        minutes >= 60 -> stringResource(SyncStrings.aimi_rc_remaining_hours_minutes, minutes / 60, minutes % 60)
        minutes > 0   -> stringResource(SyncStrings.aimi_rc_remaining_minutes, minutes)
        else          -> stringResource(SyncStrings.aimi_rc_expired)
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(AapsSpacing.large),
            horizontalArrangement = Arrangement.spacedBy(AapsSpacing.medium),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(imageVector = item.mode.category.icon(), contentDescription = null)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AapsSpacing.small)) {
                Text(text = stringResource(item.mode.name), style = MaterialTheme.typography.titleMedium)
                Text(text = remaining, style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(
                    progress = { item.progressPercent(now) / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun LlmTab(busy: Boolean, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(AapsSpacing.extraLarge),
        verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium)
    ) {
        Text(text = stringResource(SyncStrings.aimi_rc_llm_header), style = MaterialTheme.typography.titleMedium)
        Text(text = stringResource(SyncStrings.aimi_rc_llm_description), style = MaterialTheme.typography.bodyMedium)
        Text(text = stringResource(SyncStrings.aimi_rc_llm_examples), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(stringResource(SyncStrings.aimi_rc_llm_hint)) },
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                onSend(text)
                text = ""
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(SyncStrings.aimi_rc_llm_send))
        }
        if (busy) Text(text = stringResource(SyncStrings.aimi_rc_llm_processing), style = MaterialTheme.typography.bodySmall)
    }
}
