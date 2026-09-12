package app.aaps.plugins.aps.openAPSAIMI.physio

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTopAppBar
import app.aaps.core.ui.compose.preference.ProvidePreferenceTheme
import app.aaps.plugins.aps.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Compose port of the parked `AIMIHealthConnectPermissionActivityMTR`.
 *
 * The old Activity also handled Health Connect's system-triggered
 * `ACTION_SHOW_PERMISSIONS_RATIONALE` intent, but that only reaches an Activity declared for it in
 * the manifest, and this module has none - that path never actually ran. This screen only covers the
 * case a user reaches on purpose, by tapping the preference.
 *
 * Not built on the app-wide [app.aaps.ui.compose.permissionsSheet.PermissionsSheet]: that sheet's
 * "is it granted" check is synchronous ([android.content.pm.PackageManager]-style), while Health
 * Connect's is a suspend call through its own [PermissionController] - a different model, not the
 * one that sheet's wiring assumes.
 */
@Composable
fun AimiHealthConnectPermissionScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val client = remember { runCatching { HealthConnectClient.getOrCreate(context) }.getOrNull() }

    var requestPermissions by remember { mutableStateOf(AIMIHealthConnectPermissions.ALL_REQUIRED_PERMISSIONS) }
    var granted by remember { mutableStateOf<Set<String>>(emptySet()) }
    var closing by remember { mutableStateOf(false) }

    val grantedMessage = stringResource(R.string.aimi_hc_permission_granted_message)
    val sdkUnavailableMessage = stringResource(R.string.aimi_hc_permission_sdk_unavailable)
    val updateRequiredMessage = stringResource(R.string.aimi_hc_permission_update_required)
    val clientErrorMessage = stringResource(R.string.aimi_hc_permission_client_error)

    val requestPermissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
    ) {
        val currentClient = client ?: return@rememberLauncherForActivityResult
        scope.launch {
            requestPermissions = AIMIHealthConnectPermissions.resolveRequestPermissions(currentClient)
            granted = currentClient.permissionController.getGrantedPermissions()
        }
    }

    LaunchedEffect(client) {
        val currentClient = client ?: return@LaunchedEffect
        requestPermissions = AIMIHealthConnectPermissions.resolveRequestPermissions(currentClient)
        granted = currentClient.permissionController.getGrantedPermissions()
    }

    val missingCore = AIMIHealthConnectPermissions.getMissingPermissions(granted)
    val missingThermal = AIMIHealthConnectPermissions.getMissingOptionalThermalPermissions(granted)
    val fullyGranted = client != null && missingCore.isEmpty() && missingThermal.isEmpty()

    LaunchedEffect(fullyGranted) {
        if (fullyGranted && !closing) {
            closing = true
            snackbarHostState.showSnackbar(grantedMessage)
            delay(1200)
            onBack()
        }
    }

    fun onGrantClick() {
        val currentClient = client
        if (currentClient == null) {
            scope.launch { snackbarHostState.showSnackbar(clientErrorMessage) }
            return
        }
        val sdkStatus = HealthConnectClient.getSdkStatus(context)
        if (sdkStatus != HealthConnectClient.SDK_AVAILABLE) {
            scope.launch {
                if (sdkStatus == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) {
                    snackbarHostState.showSnackbar(updateRequiredMessage)
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata"))
                        )
                    }
                } else {
                    snackbarHostState.showSnackbar(sdkUnavailableMessage)
                    openHealthConnectSettings(context)
                }
            }
            return
        }
        scope.launch {
            requestPermissions = AIMIHealthConnectPermissions.resolveRequestPermissions(currentClient)
            requestPermissionLauncher.launch(requestPermissions)
        }
    }

    ProvidePreferenceTheme {
        Scaffold(
            topBar = {
                AapsTopAppBar(
                    title = { Text(stringResource(R.string.aimi_hc_permission_screen_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(app.aaps.core.ui.R.string.back),
                            )
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
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
                Text(
                    text = stringResource(R.string.aimi_hc_permission_intro),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (client == null) {
                    Text(
                        text = sdkUnavailableMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.aimi_hc_permission_required_header),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    AIMIHealthConnectPermissions.ALL_REQUIRED_PERMISSIONS.forEach { permission ->
                        HcPermissionRow(
                            name = AIMIHealthConnectPermissions.displayName(permission),
                            granted = granted.contains(permission),
                        )
                    }
                    Text(
                        text = stringResource(R.string.aimi_hc_permission_optional_header),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    AIMIHealthConnectPermissions.THERMAL_OPTIONAL_PERMISSIONS.forEach { permission ->
                        HcPermissionRow(
                            name = AIMIHealthConnectPermissions.displayName(permission),
                            granted = granted.contains(permission),
                        )
                    }
                    if (missingThermal.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.aimi_hc_permission_thermal_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Button(
                    onClick = { onGrantClick() },
                    enabled = !fullyGranted,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        when {
                            missingCore.isNotEmpty()    -> stringResource(R.string.aimi_hc_permission_grant_button_with_count, missingCore.size)
                            missingThermal.isNotEmpty() -> stringResource(R.string.aimi_hc_permission_optional_only_button)
                            else                        -> stringResource(R.string.aimi_hc_permission_all_granted_button)
                        }
                    )
                }
                Button(
                    onClick = { openHealthConnectSettings(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.aimi_hc_permission_open_settings_button))
                }
            }
        }
    }
}

@Composable
private fun HcPermissionRow(name: String, granted: Boolean) {
    ListItem(
        leadingContent = {
            if (granted) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        },
        headlineContent = { Text(name) },
    )
}

/**
 * Uses the Jetpack action so it lands on this app's own Health Connect permissions page directly,
 * with the app-details fallback the Jetpack action itself can need on some OEM builds.
 */
private fun openHealthConnectSettings(context: android.content.Context) {
    val intent = if (Build.VERSION.SDK_INT >= 34) {
        Intent("android.health.connect.action.MANAGE_HEALTH_PERMISSIONS").apply {
            putExtra(Intent.EXTRA_PACKAGE_NAME, context.packageName)
        }
    } else {
        Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
    }
    runCatching { context.startActivity(intent) }.onFailure {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            )
        }
    }
}
