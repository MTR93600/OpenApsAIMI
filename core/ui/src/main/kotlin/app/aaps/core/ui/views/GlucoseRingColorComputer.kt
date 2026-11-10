package app.aaps.core.ui.views

import android.graphics.Color
import androidx.annotation.ColorInt

/**
 * Pure palette logic for glucose ring UIs: [app.aaps.core.ui.views.GlucoseRingView],
 * [app.aaps.core.ui.compose.dashboard.GlucoseHeroRing] (unit-tested, no Android View).
 */
object GlucoseRingColorComputer {

    @ColorInt
    fun compute(
        bgMgdl: Int?,
        hypoMaxFromProfile: Float?,
        severeHypoMaxMgdl: Float,
        hypoMaxMgdlAttr: Float,
        useSteppedColors: Boolean,
        step1MaxMgdl: Float,
        step2MaxMgdl: Float,
        step3MaxMgdl: Float,
        @ColorInt stepColor1: Int,
        @ColorInt stepColor2: Int,
        @ColorInt stepColor3: Int,
        @ColorInt stepColor4: Int,
    ): Int {
        val v = bgMgdl ?: return Color.GRAY
        val hypoCap = (hypoMaxFromProfile ?: hypoMaxMgdlAttr).coerceAtLeast(severeHypoMaxMgdl + 1f)
        val vf = v.toFloat()
        if (!useSteppedColors) {
            return when {
                vf < severeHypoMaxMgdl -> stepColor4
                vf < hypoCap -> stepColor3
                vf <= 180f -> stepColor1
                else -> stepColor3
            }
        }
        return when {
            vf < severeHypoMaxMgdl -> stepColor4
            vf < hypoCap -> stepColor3
            vf <= step1MaxMgdl -> stepColor1
            vf <= step2MaxMgdl -> stepColor2
            vf <= step3MaxMgdl -> stepColor3
            else -> stepColor4
        }
    }
}
