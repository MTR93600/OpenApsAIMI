package app.aaps.plugins.main.general.overview.glass

import android.app.Dialog
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.viewModels
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.database.impl.AppRepository
import dagger.android.support.DaggerDialogFragment
import kotlinx.coroutines.delay
import javax.inject.Inject

class GlassSensorInsertDialogFragment : DaggerDialogFragment() {

    @Inject lateinit var repository: AppRepository
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var sp: SP
    @Inject lateinit var uel: UserEntryLogger
    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var protectionCheck: ProtectionCheck

    /** Called on the main thread after a successful save (after auto-dismiss feedback). */
    var onSaved: (() -> Unit)? = null

    private val viewModel: GlassSensorInsertViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, 0)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val isDark = try {
            sp.getString(app.aaps.core.utils.R.string.key_use_dark_mode, "dark") == "dark"
        } catch (e: Exception) {
            true
        }

        viewModel.init(repository, dateUtil, uel, aapsLogger, profileFunction, isDark)

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val uiState by viewModel.uiState.collectAsState()

                if (uiState.isSaved) {
                    LaunchedEffect(Unit) {
                        delay(900)
                        try {
                            dismiss()
                        } catch (e: Exception) {
                            aapsLogger.error(LTag.UI, "Error dismissing sensor dialog", e)
                        }
                        try {
                            onSaved?.invoke()
                        } catch (e: Exception) {
                            aapsLogger.error(LTag.UI, "Error in sensor dialog onSaved callback", e)
                        }
                    }
                }

                GlassOverviewTheme(isDarkMode = isDark) {
                    GlassSensorInsertDialogScreen(
                        isDark = isDark,
                        formattedDate = uiState.formattedDate,
                        formattedTime = uiState.formattedTime,
                        notes = uiState.notes,
                        isSaving = uiState.isSaving,
                        isSaved = uiState.isSaved,
                        errorMessage = uiState.errorMessage,
                        eventTimestamp = uiState.eventTimestamp,
                        onNotesChange = { viewModel.updateNotes(it) },
                        onDateSet = { y, m, d -> viewModel.updateDate(y, m, d) },
                        onTimeSet = { h, min -> viewModel.updateTime(h, min) },
                        onSetToNow = { viewModel.resetToNow() },
                        onDismiss = { dismiss() },
                        onSave = { viewModel.save() }
                    )
                }
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            window.setLayout(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            window.setGravity(Gravity.CENTER)
            window.attributes.windowAnimations = android.R.style.Animation_Dialog
        }
        return dialog
    }

    override fun onResume() {
        super.onResume()
        activity?.let { activity ->
            protectionCheck.queryProtection(
                activity,
                ProtectionCheck.Protection.BOLUS,
                {},
                {
                    aapsLogger.debug(LTag.APS, "Dialog canceled on resume protection: ${this.javaClass.simpleName}")
                    dismiss()
                },
                {
                    aapsLogger.debug(LTag.APS, "Dialog canceled on resume protection: ${this.javaClass.simpleName}")
                    dismiss()
                }
            )
        }
    }
}
