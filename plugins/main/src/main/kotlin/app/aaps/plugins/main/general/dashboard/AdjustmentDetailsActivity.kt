package app.aaps.plugins.main.general.dashboard

import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import app.aaps.core.ui.activities.TranslatedDaggerAppCompatActivity
import app.aaps.plugins.main.R
import app.aaps.plugins.main.databinding.ActivityAdjustmentDetailsBinding
import app.aaps.plugins.main.general.dashboard.viewmodel.AdjustmentCardState
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.ui.toast.ToastUtils
import javax.inject.Inject

class AdjustmentDetailsActivity : TranslatedDaggerAppCompatActivity() {

    companion object {
        const val EXTRA_ADJUSTMENT_STATE = "extra_adjustment_state"
    }

    @Inject lateinit var loop: Loop
    @Inject lateinit var aapsLogger: AAPSLogger

    private lateinit var binding: ActivityAdjustmentDetailsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdjustmentDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.title = getString(R.string.dashboard_adjustments_details_title)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        val state = intent.getSerializableExtra(EXTRA_ADJUSTMENT_STATE) as? AdjustmentCardState
        if (state == null) {
            finish()
            return
        }
        binding.adjustmentSummary.update(state)

        binding.peakTimeValue.text = state.peakTime?.let { "%.0f min".format(it) } ?: "--"
        binding.diaValue.text = state.dia?.let { "%.1f h".format(it) } ?: "--"
        binding.targetBgValue.text = state.targetBg?.let { "%.0f".format(it) } ?: "--"
        binding.smbValue.text = state.smb?.let { "%.2f U".format(it) } ?: "--"
        binding.basalValue.text = state.basal?.let { "%.2f U/h".format(it) } ?: "--"

        val reasonToShow = state.detailedReason ?: state.reason
        if (!reasonToShow.isNullOrEmpty()) {
            binding.reasonCard.isVisible = true
            binding.reasonText.text = reasonToShow
        } else {
            binding.reasonCard.isVisible = false
        }

        renderDecisions(state.adjustments)

        binding.runLoopButton.setOnClickListener {
            ToastUtils.infoToast(this, "Loop run requested")
            Thread {
                try {
                    loop.invoke("AdjustmentDetails", true)
                } catch (e: Exception) {
                    aapsLogger.error(LTag.APS, "Error invoking loop from details", e)
                }
            }.start()
        }
    }

    private fun renderDecisions(decisions: List<String>) {
        binding.decisionsEmpty.isVisible = decisions.isEmpty()
        binding.decisionsContainer.isVisible = decisions.isNotEmpty()
        binding.decisionsContainer.removeAllViews()
        if (decisions.isEmpty()) return
        val spacing = resources.getDimensionPixelSize(R.dimen.dashboard_chip_spacing)
        decisions.forEach { text ->
            val row = layoutInflater.inflate(
                R.layout.item_adjustment_detail,
                binding.decisionsContainer,
                false
            ) as LinearLayout
            val content = row.findViewById<TextView>(R.id.detail_text)
            content.text = text
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = spacing }
            binding.decisionsContainer.addView(row, params)
        }
    }
}
