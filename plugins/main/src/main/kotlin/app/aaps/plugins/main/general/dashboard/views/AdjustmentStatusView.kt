package app.aaps.plugins.main.general.dashboard.views

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import com.google.android.material.card.MaterialCardView
import app.aaps.plugins.main.databinding.ComponentAdjustmentStatusBinding

class AdjustmentStatusView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : MaterialCardView(context, attrs, defStyleAttr) {

    private val binding = ComponentAdjustmentStatusBinding.inflate(LayoutInflater.from(context), this)

    fun update(adjustments: List<String>) {
        binding.adjustmentContainer.removeAllViews()
        if (adjustments.isEmpty()) {
            binding.emptyMessage.visibility = View.VISIBLE
            return
        }
        binding.emptyMessage.visibility = View.GONE
        adjustments.forEach { text ->
            val textView = TextView(context).apply {
                this.text = text
                TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_MaterialComponents_Body2)
            }
            binding.adjustmentContainer.addView(textView)
        }
    }
}
