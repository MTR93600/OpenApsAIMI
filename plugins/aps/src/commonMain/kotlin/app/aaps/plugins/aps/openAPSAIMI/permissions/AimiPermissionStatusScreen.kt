package app.aaps.plugins.aps.openAPSAIMI.permissions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTopAppBar
import app.aaps.core.ui.compose.preference.ProvidePreferenceTheme
import app.aaps.core.ui.compose.stringResource
import app.aaps.plugins.aps.ApsStrings
import kotlinx.coroutines.launch

/** What the system says about one permission, as shown on [AimiPermissionStatusScreen]. */
enum class AimiPermissionState(val label: TextRef) {

    GRANTED(ApsStrings.aimi_permission_state_granted),

    /** Refused. Only the system settings can change it now. */
    DENIED(ApsStrings.aimi_permission_state_denied),

    /** Never asked. The system shows its own question the first time the app asks. */
    NOT_ASKED(ApsStrings.aimi_permission_state_not_asked),

    /**
     * Asked and answered, but the system does not tell the app what the answer was. Apple Health
     * read access works like this on purpose.
     */
    ANSWERED(ApsStrings.aimi_permission_state_answered),

    /** This device cannot give this permission at all (for example Apple Health on an iPad). */
    UNAVAILABLE(ApsStrings.aimi_permission_state_unavailable),
}

/** One row of [AimiPermissionStatusScreen]. */
data class AimiPermissionRow(val name: TextRef, val state: AimiPermissionState)

/**
 * A small screen that shows the state of the permissions one AIMI feature needs, with a button to
 * ask for them and, when the platform has one, a button to open the system settings.
 *
 * Only the platform knows how to read and ask for a permission, so it passes [loadRows], [ask] and
 * [openSettings]. The rows are read again each time the screen comes back to the front (the user
 * may have changed something in the system settings) and after [ask].
 *
 * Used on iOS for the SOS alert notifications and for Apple Health. Android keeps its own screens,
 * which use the Android permission launchers.
 */
@Composable
fun AimiPermissionStatusScreen(
    title: TextRef,
    intro: TextRef,
    note: TextRef?,
    loadRows: suspend () -> List<AimiPermissionRow>,
    ask: suspend () -> Unit,
    openSettings: (() -> Unit)?,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var rows by remember { mutableStateOf<List<AimiPermissionRow>>(emptyList()) }
    var asking by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch { rows = loadRows() }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun onAsk() {
        if (asking) return
        asking = true
        scope.launch {
            try {
                ask()
                rows = loadRows()
            } finally {
                asking = false
            }
        }
    }

    val canAsk = rows.any { it.state == AimiPermissionState.NOT_ASKED }

    ProvidePreferenceTheme {
        Scaffold(
            topBar = {
                AapsTopAppBar(
                    title = { Text(stringResource(title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(CoreUiStrings.back),
                            )
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .padding(horizontal = AapsSpacing.large)
                    .padding(bottom = AapsSpacing.xxLarge)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium),
            ) {
                Text(text = stringResource(intro), style = MaterialTheme.typography.bodyMedium)
                rows.forEach { row -> PermissionStateRow(row) }
                note?.let {
                    Text(
                        text = stringResource(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = { onAsk() },
                    enabled = canAsk && !asking,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(ApsStrings.aimi_permission_ask_button))
                }
                if (openSettings != null) {
                    OutlinedButton(onClick = openSettings, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(ApsStrings.aimi_permission_open_settings_button))
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionStateRow(row: AimiPermissionRow) {
    ListItem(
        leadingContent = {
            when (row.state) {
                AimiPermissionState.GRANTED     -> Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )

                AimiPermissionState.DENIED,
                AimiPermissionState.UNAVAILABLE -> Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )

                AimiPermissionState.NOT_ASKED,
                AimiPermissionState.ANSWERED    -> Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        headlineContent = { Text(stringResource(row.name)) },
        supportingContent = { Text(stringResource(row.state.label)) },
    )
}
