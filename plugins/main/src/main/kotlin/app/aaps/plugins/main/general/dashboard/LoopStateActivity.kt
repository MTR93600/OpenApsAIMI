package app.aaps.plugins.main.general.dashboard

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.content.res.AppCompatResources
import app.aaps.core.data.model.RM
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.activities.TranslatedDaggerAppCompatActivity
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.plugins.main.R
import app.aaps.plugins.main.databinding.ActivityLoopStateBinding
import com.google.android.material.button.MaterialButton
import javax.inject.Inject
import app.aaps.core.data.time.T

class LoopStateActivity : TranslatedDaggerAppCompatActivity() {

    @Inject lateinit var loop: Loop
    @Inject lateinit var resourceHelper: ResourceHelper
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var config: Config
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var preferences: Preferences

    private lateinit var binding: ActivityLoopStateBinding
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoopStateBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    override fun onResume() {
        super.onResume()
        renderActions()
    }

    private fun renderActions() {
        val allowedModes = loop.allowedNextModes()
        val runningMode = loop.runningModeRecord.mode
        val pumpDescription = activePlugin.activePump.pumpDescription
        
        binding.loopActionsContainer.removeAllViews()
        val spacing = resources.getDimensionPixelSize(R.dimen.dashboard_chip_spacing)

        fun addButton(title: String, iconRes: Int, onClick: () -> Unit) {
            val icon = AppCompatResources.getDrawable(this, iconRes)
            val button = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = title
                iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                iconPadding = spacing
                setOnClickListener {
                    OKDialog.showConfirmation(
                        this@LoopStateActivity,
                        "${resourceHelper.gs(app.aaps.core.ui.R.string.confirm)}: $title"
                    ) {
                        handler.post { onClick() }
                        finish()
                    }
                }
            }
            icon?.mutate()?.setTint(resourceHelper.gac(this, app.aaps.core.ui.R.attr.userOptionColor))
            button.icon = icon
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = spacing }
            binding.loopActionsContainer.addView(button, params)
        }

        val profile = profileFunction.getProfile() ?: return

        if (allowedModes.contains(RM.Mode.CLOSED_LOOP)) {
            addButton(resourceHelper.gs(app.aaps.core.ui.R.string.closedloop), app.aaps.core.objects.R.drawable.ic_loop_closed) {
                loop.handleRunningModeChange(newRM = RM.Mode.CLOSED_LOOP, action = Action.CLOSED_LOOP_MODE, source = Sources.LoopDialog, profile = profile)
            }
        }
        if (allowedModes.contains(RM.Mode.CLOSED_LOOP_LGS)) {
            addButton(resourceHelper.gs(app.aaps.core.ui.R.string.lowglucosesuspend), app.aaps.core.ui.R.drawable.ic_loop_lgs) {
                loop.handleRunningModeChange(newRM = RM.Mode.CLOSED_LOOP_LGS, action = Action.LGS_LOOP_MODE, source = Sources.LoopDialog, profile = profile)
            }
        }
        if (allowedModes.contains(RM.Mode.OPEN_LOOP)) {
            addButton(resourceHelper.gs(app.aaps.core.ui.R.string.openloop), app.aaps.core.ui.R.drawable.ic_loop_open) {
                loop.handleRunningModeChange(newRM = RM.Mode.OPEN_LOOP, action = Action.OPEN_LOOP_MODE, source = Sources.LoopDialog, profile = profile)
            }
        }
        if (allowedModes.contains(RM.Mode.DISABLED_LOOP)) {
            addButton(resourceHelper.gs(app.aaps.core.ui.R.string.disableloop), app.aaps.core.ui.R.drawable.ic_loop_disabled) {
                loop.handleRunningModeChange(newRM = RM.Mode.DISABLED_LOOP, durationInMinutes = Int.MAX_VALUE, action = Action.LOOP_DISABLED, source = Sources.LoopDialog, profile = profile)
            }
        }
        if (allowedModes.contains(RM.Mode.RESUME)) {
             val action = if (runningMode == RM.Mode.DISCONNECTED_PUMP) Action.RECONNECT else Action.RESUME
             val title = if (runningMode == RM.Mode.DISCONNECTED_PUMP) resourceHelper.gs(app.aaps.core.ui.R.string.reconnect) else resourceHelper.gs(app.aaps.core.ui.R.string.resume)
             addButton(title, app.aaps.core.ui.R.drawable.ic_loop_resume) {
                loop.handleRunningModeChange(newRM = RM.Mode.RESUME, action = action, source = Sources.LoopDialog, profile = profile)
                if (runningMode == RM.Mode.DISCONNECTED_PUMP) preferences.put(BooleanNonKey.ObjectivesReconnectUsed, true)
            }
        }
        if (allowedModes.contains(RM.Mode.SUSPENDED_BY_USER)) {
            addButton(resourceHelper.gs(app.aaps.core.ui.R.string.suspendloopfor1h), app.aaps.core.ui.R.drawable.ic_loop_paused) {
                loop.handleRunningModeChange(newRM = RM.Mode.SUSPENDED_BY_USER, durationInMinutes = T.hours(1).mins().toInt(), action = Action.SUSPEND, source = Sources.LoopDialog, profile = profile)
            }
            addButton(resourceHelper.gs(app.aaps.core.ui.R.string.suspendloopfor2h), app.aaps.core.ui.R.drawable.ic_loop_paused) {
                loop.handleRunningModeChange(newRM = RM.Mode.SUSPENDED_BY_USER, durationInMinutes = T.hours(2).mins().toInt(), action = Action.SUSPEND, source = Sources.LoopDialog, profile = profile)
            }
             addButton(resourceHelper.gs(app.aaps.core.ui.R.string.suspendloopfor3h), app.aaps.core.ui.R.drawable.ic_loop_paused) {
                loop.handleRunningModeChange(newRM = RM.Mode.SUSPENDED_BY_USER, durationInMinutes = T.hours(3).mins().toInt(), action = Action.SUSPEND, source = Sources.LoopDialog, profile = profile)
            }
             addButton(resourceHelper.gs(app.aaps.core.ui.R.string.suspendloopfor10h), app.aaps.core.ui.R.drawable.ic_loop_paused) {
                loop.handleRunningModeChange(newRM = RM.Mode.SUSPENDED_BY_USER, durationInMinutes = T.hours(10).mins().toInt(), action = Action.SUSPEND, source = Sources.LoopDialog, profile = profile)
            }
        }
        if (allowedModes.contains(RM.Mode.DISCONNECTED_PUMP) && config.APS) {
             if (pumpDescription.tempDurationStep15mAllowed) {
                addButton(resourceHelper.gs(app.aaps.core.ui.R.string.disconnectpumpfor15m), app.aaps.core.ui.R.drawable.ic_loop_disconnected) {
                    loop.handleRunningModeChange(newRM = RM.Mode.DISCONNECTED_PUMP, durationInMinutes = 15, action = Action.DISCONNECT, source = Sources.LoopDialog, profile = profile)
                }
             }
             if (pumpDescription.tempDurationStep30mAllowed) {
                addButton(resourceHelper.gs(app.aaps.core.ui.R.string.disconnectpumpfor30m), app.aaps.core.ui.R.drawable.ic_loop_disconnected) {
                    loop.handleRunningModeChange(newRM = RM.Mode.DISCONNECTED_PUMP, durationInMinutes = 30, action = Action.DISCONNECT, source = Sources.LoopDialog, profile = profile)
                }
             }
            addButton(resourceHelper.gs(app.aaps.core.ui.R.string.disconnectpumpfor1h), app.aaps.core.ui.R.drawable.ic_loop_disconnected) {
                loop.handleRunningModeChange(newRM = RM.Mode.DISCONNECTED_PUMP, durationInMinutes = 60, action = Action.DISCONNECT, source = Sources.LoopDialog, profile = profile)
                preferences.put(BooleanNonKey.ObjectivesDisconnectUsed, true)
            }
            addButton(resourceHelper.gs(app.aaps.core.ui.R.string.disconnectpumpfor2h), app.aaps.core.ui.R.drawable.ic_loop_disconnected) {
                loop.handleRunningModeChange(newRM = RM.Mode.DISCONNECTED_PUMP, durationInMinutes = 120, action = Action.DISCONNECT, source = Sources.LoopDialog, profile = profile)
            }
            addButton(resourceHelper.gs(app.aaps.core.ui.R.string.disconnectpumpfor3h), app.aaps.core.ui.R.drawable.ic_loop_disconnected) {
                loop.handleRunningModeChange(newRM = RM.Mode.DISCONNECTED_PUMP, durationInMinutes = 180, action = Action.DISCONNECT, source = Sources.LoopDialog, profile = profile)
            }
        }
    }
}
