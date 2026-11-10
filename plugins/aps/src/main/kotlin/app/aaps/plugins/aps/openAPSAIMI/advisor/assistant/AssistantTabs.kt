package app.aaps.plugins.aps.openAPSAIMI.advisor.assistant

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Three tabs at the top of the AIMI Assistant screens: Settings, Insight and Eating. They look like one section: a tap on the
 * other tab opens it in place of the current screen, without a transition.
 */
object AssistantTabs {

    fun bar(activity: Activity, selected: Int): LinearLayout {
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 24)
        }
        fun tab(label: String, index: Int, target: Class<out Activity>) {
            val on = index == selected
            bar.addView(
                TextView(activity).apply {
                    text = label
                    textSize = 15f
                    gravity = Gravity.CENTER
                    setPadding(24, 20, 24, 20)
                    setTextColor(if (on) Color.WHITE else AssistantUi.MUTED)
                    setBackgroundColor(if (on) Color.parseColor("#1E3A5F") else AssistantUi.CARD)
                    if (!on) setOnClickListener {
                        activity.startActivity(Intent(activity, target).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
                        activity.finish()
                        @Suppress("DEPRECATION")
                        activity.overridePendingTransition(0, 0)
                    }
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
        tab("Настройки", 0, AimiAssistantActivity::class.java)
        tab("Insight", 1, AimiInsightActivity::class.java)
        tab("Я ем", 2, AimiEatingActivity::class.java)
        return bar
    }
}
