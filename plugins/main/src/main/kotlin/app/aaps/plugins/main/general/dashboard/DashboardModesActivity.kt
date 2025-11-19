package app.aaps.plugins.main.general.dashboard

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.isVisible
import app.aaps.core.interfaces.automation.Automation
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.ui.activities.TranslatedDaggerAppCompatActivity
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.plugins.main.R
import app.aaps.plugins.main.databinding.ActivityDashboardModesBinding
import com.google.android.material.button.MaterialButton
import javax.inject.Inject

class DashboardModesActivity : TranslatedDaggerAppCompatActivity() {

    @Inject lateinit var automation: Automation
    @Inject lateinit var resourceHelper: ResourceHelper

    private lateinit var binding: ActivityDashboardModesBinding
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDashboardModesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.title = resourceHelper.gs(R.string.dashboard_nav_modes)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    override fun onResume() {
        super.onResume()
        renderActions()
    }

    private fun renderActions() {
        val actions = automation.userEvents().filter { it.isEnabled && it.canRun() }
        binding.modesEmpty.isVisible = actions.isEmpty()
        binding.actionsContainer.removeAllViews()
        val spacing = resources.getDimensionPixelSize(R.dimen.dashboard_chip_spacing)
        actions.forEach { event ->
            val icon = AppCompatResources.getDrawable(
                this,
                event.firstActionIcon() ?: app.aaps.core.ui.R.drawable.ic_user_options_24dp
            )
            val button = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = event.title
                iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                iconPadding = spacing
                setOnClickListener {
                    OKDialog.showConfirmation(
                        this@DashboardModesActivity,
                        resourceHelper.gs(app.aaps.core.ui.R.string.run_question, event.title)
                    ) {
                        handler.post { automation.processEvent(event) }
                    }
                }
            }
            icon?.mutate()?.setTint(resourceHelper.gac(this, app.aaps.core.ui.R.attr.userOptionColor))
            button.icon = icon
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = spacing }
            binding.actionsContainer.addView(button, params)
        }
    }
}
