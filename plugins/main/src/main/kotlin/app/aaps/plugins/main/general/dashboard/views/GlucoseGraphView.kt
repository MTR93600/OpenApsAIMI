package app.aaps.plugins.main.general.dashboard.views

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import com.google.android.material.card.MaterialCardView
import app.aaps.plugins.main.databinding.ViewGlucoseGraphPlaceholderBinding

class GlucoseGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : MaterialCardView(context, attrs, defStyleAttr) {

    private val binding = ViewGlucoseGraphPlaceholderBinding.inflate(LayoutInflater.from(context), this, true)

    fun update(message: String) {
        binding.graphPlaceholder.text = message
    }
}
