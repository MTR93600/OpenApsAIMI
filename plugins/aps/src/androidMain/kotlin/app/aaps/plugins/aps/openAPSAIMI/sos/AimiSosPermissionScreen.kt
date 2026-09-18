package app.aaps.plugins.aps.openAPSAIMI.sos

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTopAppBar
import app.aaps.core.ui.compose.preference.ProvidePreferenceTheme
import app.aaps.plugins.aps.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val REQUIRED_FOREGROUND_PERMISSIONS = arrayOf(
    Manifest.permission.SEND_SMS,
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

/**
 * Compose port of the parked `AIMIEmergencySosPermissionActivityMTR`.
 *
 * Background location has to be requested in its own separate system dialog after the foreground
 * three are granted - Android will not grant it in the same request. SMS-only SOS: no CALL_PHONE.
 * Location is only used for the maps link in the SMS body.
 *
 * Not built on the app-wide [app.aaps.ui.compose.permissionsSheet.PermissionsSheet]: that would need
 * this screen to also route through the global `PermissionsViewModel`, which is a bigger, shared
 * change - this screen instead checks [ContextCompat.checkSelfPermission] itself, same as the
 * Activity it replaces.
 */
@Composable
fun AimiSosPermissionScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current

    var refreshTrigger by remember { mutableStateOf(0) }
    var closing by remember { mutableStateOf(false) }

    val foregroundDeniedMessage = stringResource(R.string.aimi_sos_permission_foreground_denied_message)
    val allGrantedMessage = stringResource(R.string.aimi_sos_permission_all_granted_message)
    val backgroundDeniedMessage = stringResource(R.string.aimi_sos_permission_background_denied_message)
    val permissionNames = mapOf(
        Manifest.permission.SEND_SMS to stringResource(R.string.aimi_sos_permission_name_sms),
        Manifest.permission.ACCESS_FINE_LOCATION to stringResource(R.string.aimi_sos_permission_name_fine_location),
        Manifest.permission.ACCESS_COARSE_LOCATION to stringResource(R.string.aimi_sos_permission_name_coarse_location),
    )
    val backgroundLocationName = stringResource(R.string.aimi_sos_permission_name_background_location)

    fun hasBackgroundLocation(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    val backgroundLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        refreshTrigger++
        scope.launch { snackbarHostState.showSnackbar(if (isGranted) allGrantedMessage else backgroundDeniedMessage) }
    }

    val foregroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        refreshTrigger++
        if (results.values.all { it }) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackgroundLocation()) {
                backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        } else {
            scope.launch { snackbarHostState.showSnackbar(foregroundDeniedMessage) }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshTrigger++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val granted = remember(refreshTrigger) {
        REQUIRED_FOREGROUND_PERMISSIONS.associateWith {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
    val backgroundGranted = remember(refreshTrigger) { hasBackgroundLocation() }
    val fullyGranted = granted.values.all { it } && backgroundGranted

    LaunchedEffect(fullyGranted) {
        if (fullyGranted && !closing) {
            closing = true
            delay(1200)
            onBack()
        }
    }

    fun onGrantClick() {
        val missing = REQUIRED_FOREGROUND_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            foregroundLauncher.launch(missing.toTypedArray())
        } else if (!backgroundGranted) {
            backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }

    ProvidePreferenceTheme {
        Scaffold(
            topBar = {
                AapsTopAppBar(
                    title = { Text(stringResource(R.string.aimi_sos_permission_screen_title)) },
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
                    text = stringResource(R.string.aimi_sos_permission_intro),
                    style = MaterialTheme.typography.bodyMedium,
                )
                granted.forEach { (permission, isGranted) ->
                    SosPermissionRow(name = permissionNames.getValue(permission), granted = isGranted)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    SosPermissionRow(name = backgroundLocationName, granted = backgroundGranted)
                }
                Button(
                    onClick = { onGrantClick() },
                    enabled = !fullyGranted,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            if (fullyGranted) R.string.aimi_sos_permission_all_granted_button
                            else R.string.aimi_sos_permission_grant_button
                        )
                    )
                }
                Button(
                    onClick = { openAppSettings(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.aimi_sos_permission_open_settings_button))
                }
            }
        }
    }
}

@Composable
private fun SosPermissionRow(name: String, granted: Boolean) {
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

private fun openAppSettings(context: android.content.Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        )
    }
}
