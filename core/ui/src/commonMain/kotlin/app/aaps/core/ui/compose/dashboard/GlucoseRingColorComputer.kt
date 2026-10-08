package app.aaps.core.ui.compose.dashboard

/**
 * Pure palette logic for glucose ring UIs: [GlucoseHeroRing] (unit-tested, no platform View).
 *
 * Ported from `core/ui` (Android) to commonMain: `android.graphics.Color.GRAY` replaced by its
 * ARGB int literal, `@ColorInt` annotations dropped (no-op in commonMain).
 */
object GlucoseRingColorComputer {

    fun compute(
        bgMgdl: Int?,
        hypoMaxFromProfile: Float?,
        severeHypoMaxMgdl: Float,
        hypoMaxMgdlAttr: Float,
        useSteppedColors: Boolean,
        step1MaxMgdl: Float,
        step2MaxMgdl: Float,
        step3MaxMgdl: Float,
        stepColor1: Int,
        stepColor2: Int,
        stepColor3: Int,
        stepColor4: Int,
    ): Int {
        val v = bgMgdl ?: return 0xFF888888.toInt()
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
