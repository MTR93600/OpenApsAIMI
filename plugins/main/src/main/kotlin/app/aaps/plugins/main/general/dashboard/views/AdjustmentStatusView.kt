package app.aaps.plugins.main.general.dashboard.views

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import com.google.android.material.card.MaterialCardView
import app.aaps.plugins.main.databinding.ComponentAdjustmentStatusBinding
import app.aaps.plugins.main.general.dashboard.viewmodel.AdjustmentCardState

class AdjustmentStatusView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : MaterialCardView(context, attrs, defStyleAttr) {

    private val binding = ComponentAdjustmentStatusBinding.inflate(LayoutInflater.from(context), this)

    private val binding = ComponentAdjustmentStatusBinding.inflate(LayoutInflater.from(context), this)

    fun setOnRunLoopClickListener(listener: OnClickListener) {
        binding.runLoopButton.setOnClickListener(listener)
    }

    fun update(state: AdjustmentCardState) {
        binding.glycemiaLine.text = state.glycemiaLine
        binding.predictionLine.text = state.predictionLine
        binding.iobActivityLine.text = state.iobActivityLine
        binding.decisionLine.text = state.decisionLine
        binding.pumpLine.text = androidx.core.text.HtmlCompat.fromHtml(state.pumpLine, androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY)
        binding.safetyLine.text = state.safetyLine
        if (state.modeLine.isNullOrBlank()) {
            binding.modeLine.visibility = View.GONE
        } else {
            binding.modeLine.text = state.modeLine
            binding.modeLine.visibility = View.VISIBLE
        }
        val adjustments = state.adjustments
        binding.adjustmentContainer.removeAllViews()
        binding.emptyMessage.visibility = if (adjustments.isEmpty()) View.VISIBLE else View.GONE
        val chipVerticalPadding = resources.getDimensionPixelSize(app.aaps.plugins.main.R.dimen.dashboard_chip_padding_vertical)
        val chipHorizontalPadding = resources.getDimensionPixelSize(app.aaps.plugins.main.R.dimen.dashboard_chip_padding_horizontal)
        val chipSpacing = resources.getDimensionPixelSize(app.aaps.plugins.main.R.dimen.dashboard_chip_spacing)
        adjustments.forEach { text ->
            val textView = TextView(context).apply {
                this.text = text
                setTextColor(ContextCompat.getColor(context, app.aaps.plugins.main.R.color.dashboard_on_surface))
                background = AppCompatResources.getDrawable(context, app.aaps.plugins.main.R.drawable.dashboard_chip_background)
                setPadding(chipHorizontalPadding, chipVerticalPadding, chipHorizontalPadding, chipVerticalPadding)
                TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_MaterialComponents_Body2)
            }
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = chipSpacing
            }
            binding.adjustmentContainer.addView(textView, params)
        }
    }
}
