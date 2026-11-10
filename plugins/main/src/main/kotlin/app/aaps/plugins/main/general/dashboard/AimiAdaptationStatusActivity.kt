package app.aaps.plugins.main.general.dashboard

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.activities.TranslatedDaggerAppCompatActivity
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.LocalPreferences
import app.aaps.plugins.main.general.dashboard.compose.AimiAdaptationStatusScreen
import app.aaps.plugins.main.general.dashboard.viewmodel.AimiAdaptationStatusViewModel
import javax.inject.Inject

class AimiAdaptationStatusActivity : TranslatedDaggerAppCompatActivity() {

    @Inject lateinit var viewModelFactory: AimiAdaptationStatusViewModel.Factory
    @Inject lateinit var preferences: Preferences

    private val viewModel: AimiAdaptationStatusViewModel by viewModels { viewModelFactory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CompositionLocalProvider(LocalPreferences provides preferences) {
                AapsTheme {
                    val state by viewModel.uiState.collectAsStateWithLifecycle()
                    AimiAdaptationStatusScreen(
                        state = state,
                        onBack = { onBackPressedDispatcher.onBackPressed() },
                    )
                }
            }
        }
    }
}
