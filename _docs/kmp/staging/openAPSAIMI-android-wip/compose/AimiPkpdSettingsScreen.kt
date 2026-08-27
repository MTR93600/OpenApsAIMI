package app.aaps.plugins.aps.openAPSAIMI.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTopAppBar
import app.aaps.core.ui.compose.LocalPreferences
import app.aaps.core.ui.compose.preference.ProvidePreferenceTheme
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.openAPSAIMI.advisor.AimiRecommendation
import app.aaps.plugins.aps.openAPSAIMI.model.AimiAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Guided PK/PD setup: simple (default), advanced tuning, and expert parameters.
 */
@Composable
fun AimiPkpdSettingsScreen(
    preferences: Preferences,
    onBack: () -> Unit,
    loadProfileInsulin: (suspend () -> Pair<Double?, Double?>)? = null,
    loadPkpdRecommendations: (suspend () -> List<AimiRecommendation>)? = null,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var preferenceRevision by remember { mutableIntStateOf(0) }
    var selectedLevel by remember { mutableStateOf(PkpdSettingsLevel.SIMPLE) }
    var showWizard by remember { mutableStateOf(false) }
    var recommendations by remember { mutableStateOf<List<AimiRecommendation>>(emptyList()) }
    var profileDiaHours by remember { mutableStateOf<Double?>(null) }
    var profilePeakMin by remember { mutableStateOf<Double?>(null) }

    LaunchedEffect(loadProfileInsulin) {
        val loader = loadProfileInsulin ?: return@LaunchedEffect
        val (dia, peak) = withContext(Dispatchers.IO) { loader() }
        profileDiaHours = dia
        profilePeakMin = peak
    }

    LaunchedEffect(loadPkpdRecommendations, preferenceRevision) {
        val loader = loadPkpdRecommendations ?: return@LaunchedEffect
        recommendations = withContext(Dispatchers.IO) { loader() }
    }

    LaunchedEffect(Unit) {
        if (!preferences.get(BooleanKey.OApsAIMIPkpdSetupWizardCompleted)) {
            showWizard = true
        }
    }

    if (showWizard) {
        PkpdSetupWizardDialog(
            preferences = preferences,
            profileDiaHours = profileDiaHours,
            onDismiss = { showWizard = false },
            onCompleted = {
                preferenceRevision++
                showWizard = false
            },
        )
    }

    ProvidePreferenceTheme {
        CompositionLocalProvider(LocalPreferences provides preferences) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    AapsTopAppBar(
                        title = { Text(stringResource(R.string.aimi_pkpd_compose_title)) },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(app.aaps.core.ui.R.string.back),
                                )
                            }
                        },
                    )
                },
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = AapsSpacing.medium, vertical = AapsSpacing.small),
                    verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium),
                ) {
                    Text(
                        stringResource(R.string.aimi_pkpd_compose_summary_v2),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    PrimaryTabRow(
                        selectedTabIndex = when (selectedLevel) {
                            PkpdSettingsLevel.SIMPLE -> 0
                            PkpdSettingsLevel.ADVANCED, PkpdSettingsLevel.EXPERT -> 1
                        },
                    ) {
                        Tab(
                            selected = selectedLevel == PkpdSettingsLevel.SIMPLE,
                            onClick = { selectedLevel = PkpdSettingsLevel.SIMPLE },
                            text = { Text(stringResource(R.string.aimi_pkpd_level_simple)) },
                        )
                        Tab(
                            selected = selectedLevel == PkpdSettingsLevel.ADVANCED ||
                                selectedLevel == PkpdSettingsLevel.EXPERT,
                            onClick = { selectedLevel = PkpdSettingsLevel.ADVANCED },
                            text = { Text(stringResource(R.string.aimi_pkpd_level_advanced)) },
                        )
                    }

                    when (selectedLevel) {
                        PkpdSettingsLevel.SIMPLE -> PkpdSimpleSettingsContent(
                            preferences = preferences,
                            profileDiaHours = profileDiaHours,
                            profilePeakMin = profilePeakMin,
                            preferenceRevision = preferenceRevision,
                            onPreferenceRevisionBump = { preferenceRevision++ },
                            recommendations = recommendations,
                            onApplyRecommendation = { action ->
                                if (applyPkpdPreferenceUpdate(preferences, action)) {
                                    preferenceRevision++
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            context.getString(R.string.aimi_pkpd_advisor_applied),
                                        )
                                    }
                                }
                            },
                            onOpenAdvanced = { selectedLevel = PkpdSettingsLevel.ADVANCED },
                            onRerunWizard = { showWizard = true },
                            snackbarHostState = snackbarHostState,
                            scope = scope,
                        )
                        PkpdSettingsLevel.ADVANCED, PkpdSettingsLevel.EXPERT -> PkpdAdvancedSettingsContent(
                            preferences = preferences,
                            preferenceRevision = preferenceRevision,
                            onPreferenceRevisionBump = { preferenceRevision++ },
                            snackbarHostState = snackbarHostState,
                            scope = scope,
                        )
                    }
                }
            }
        }
    }
}
