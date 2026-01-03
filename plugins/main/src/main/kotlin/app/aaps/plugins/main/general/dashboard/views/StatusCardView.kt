package app.aaps.plugins.main.general.dashboard.views

import android.content.Context
import android.graphics.Paint
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import com.google.android.material.card.MaterialCardView
import app.aaps.plugins.main.databinding.ComponentStatusCardBinding
import app.aaps.plugins.main.general.dashboard.viewmodel.StatusCardState
import app.aaps.plugins.main.R

class StatusCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : MaterialCardView(context, attrs, defStyleAttr) {

    private val binding = ComponentStatusCardBinding.inflate(LayoutInflater.from(context), this)

    init {
        isClickable = true
        isFocusable = true
    }

    fun setOnAimiIconClickListener(listener: OnClickListener) {
        findViewById<View>(R.id.aimi_context_indicator)?.setOnClickListener(listener)
    }

    fun update(state: StatusCardState) {
        binding.glucoseValue.text = state.glucoseText
        binding.glucoseValue.setTextColor(state.glucoseColor)
        binding.trendArrow.visibility = if (state.trendArrowRes == null) View.GONE else View.VISIBLE
        state.trendArrowRes?.let { binding.trendArrow.setImageResource(it) }
        binding.trendArrow.contentDescription = state.trendDescription
        binding.loopStatus.text = state.loopStatusText
        binding.timeAgo.text = state.timeAgo
        binding.deltaValue.text = state.deltaText
        binding.timeAgo.contentDescription = state.timeAgoDescription
        binding.glucoseValue.paintFlags =
            if (state.isGlucoseActual) binding.glucoseValue.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            else binding.glucoseValue.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        //binding.trendDescription.text = state.trendDescription
        binding.loopIndicator.alpha = if (state.loopIsRunning) 1f else 0.4f
        binding.iobText.text = state.iobText
        binding.pumpStatusText.text = androidx.core.text.HtmlCompat.fromHtml(state.pumpStatusText, androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY)
        binding.predictionText.text = state.predictionText
        binding.unicornIcon.setImageResource(state.unicornImageRes)  // 🦄 Update dynamic unicorn image
        
        findViewById<View>(R.id.aimi_context_indicator)?.visibility = 
            if (state.isAimiContextActive) View.VISIBLE else View.GONE
            
        contentDescription = state.contentDescription
    }
}
