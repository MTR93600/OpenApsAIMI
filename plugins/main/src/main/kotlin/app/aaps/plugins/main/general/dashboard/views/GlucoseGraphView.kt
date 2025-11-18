package app.aaps.plugins.main.general.dashboard.views

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import com.google.android.material.card.MaterialCardView
import com.jjoe64.graphview.GraphView
import app.aaps.plugins.main.databinding.ViewGlucoseGraphPlaceholderBinding

class GlucoseGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : MaterialCardView(context, attrs, defStyleAttr) {

    private val binding = ViewGlucoseGraphPlaceholderBinding.inflate(LayoutInflater.from(context), this, true)

    val graph: GraphView get() = binding.graphView

    fun setUpdateMessage(message: String) {
        binding.graphUpdatedAt.text = message
    }

    fun showPlaceholder(show: Boolean) {
        binding.graphPlaceholder.visibility = if (show) View.VISIBLE else View.GONE
        binding.graphView.visibility = if (show) View.GONE else View.VISIBLE
    }
}
