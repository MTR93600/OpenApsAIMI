package app.aaps.ui.compose.overview.graphs

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * Values of `StringKey.OverviewVicoBgReadingTint` and `StringKey.OverviewVicoChartBackdrop`, the two
 * "Home graph (Vico) appearance" settings. Ported from dev_OAPSAIMI with the same values and colors.
 */
object VicoChartAppearance {

    const val TINT_THEME = "theme"
    const val TINT_MAUVE = "mauve"
    const val TINT_OCEAN = "ocean"
    const val TINT_AMBER = "amber"
    const val TINT_ROSE = "rose"

    const val BACKDROP_THEME = "theme"
    const val BACKDROP_DEEP = "deep"
    const val BACKDROP_WARM = "warm"
    const val BACKDROP_COOL = "cool"
    const val BACKDROP_MUTED = "muted"
}

/** The two look settings of the BG graph, as read from the preferences. */
@Immutable
data class VicoChartLook(
    val bgReadingTintKey: String,
    val chartBackdropKey: String,
)

/**
 * Discrete BG dot / outline tint. [themeDefault] is the color the graph uses without the setting
 * (`AapsTheme.generalColors.originalBgValue`).
 */
fun bgReadingTintColor(tintKey: String, themeDefault: Color): Color =
    when (tintKey) {
        VicoChartAppearance.TINT_THEME -> themeDefault
        VicoChartAppearance.TINT_MAUVE -> Color(0xFF9C86B3)
        VicoChartAppearance.TINT_OCEAN -> Color(0xFF3F8FA8)
        VicoChartAppearance.TINT_AMBER -> Color(0xFFD4A43C)
        VicoChartAppearance.TINT_ROSE -> Color(0xFFC96B8A)
        else -> themeDefault
    }

data class VicoChartBackdropPalette(
    val top: Color,
    val mid: Color,
    val bottom: Color,
    val vignette: Color,
    val wash: Color,
)

/**
 * Layered gradient colors behind the dashboard Vico host (matches prior Material3-based defaults for [theme]).
 */
fun vicoChartBackdropPalette(backdropKey: String, scheme: ColorScheme): VicoChartBackdropPalette {
    val baseTop = scheme.surfaceContainerHighest
    val baseMid = scheme.surface
    val baseBottom = scheme.surfaceContainerLow
    val baseVignette = scheme.primary
    val baseWash = scheme.primary
    return when (backdropKey) {
        VicoChartAppearance.BACKDROP_THEME -> VicoChartBackdropPalette(
            top = baseTop.copy(alpha = 0.48f),
            mid = baseMid.copy(alpha = 0.94f),
            bottom = baseBottom.copy(alpha = 0.82f),
            vignette = baseVignette.copy(alpha = 0.04f),
            wash = baseWash.copy(alpha = 0.028f),
        )
        VicoChartAppearance.BACKDROP_DEEP -> VicoChartBackdropPalette(
            top = scheme.surfaceContainerLowest.copy(alpha = 0.72f),
            mid = scheme.surfaceContainerLow.copy(alpha = 0.96f),
            bottom = scheme.surfaceContainerHighest.copy(alpha = 0.88f),
            vignette = baseVignette.copy(alpha = 0.06f),
            wash = baseWash.copy(alpha = 0.04f),
        )
        VicoChartAppearance.BACKDROP_WARM -> VicoChartBackdropPalette(
            top = baseTop.copy(alpha = 0.5f),
            mid = Color(0xFF3D3428).copy(alpha = 0.35f).compositeOver(baseMid.copy(alpha = 0.94f)),
            bottom = baseBottom.copy(alpha = 0.85f),
            vignette = scheme.tertiary.copy(alpha = 0.05f),
            wash = scheme.tertiary.copy(alpha = 0.03f),
        )
        VicoChartAppearance.BACKDROP_COOL -> VicoChartBackdropPalette(
            top = baseTop.copy(alpha = 0.5f),
            mid = Color(0xFF28323D).copy(alpha = 0.32f).compositeOver(baseMid.copy(alpha = 0.94f)),
            bottom = baseBottom.copy(alpha = 0.84f),
            vignette = scheme.secondary.copy(alpha = 0.05f),
            wash = scheme.secondary.copy(alpha = 0.03f),
        )
        VicoChartAppearance.BACKDROP_MUTED -> VicoChartBackdropPalette(
            top = baseTop.copy(alpha = 0.38f),
            mid = baseMid.copy(alpha = 0.9f),
            bottom = baseBottom.copy(alpha = 0.72f),
            vignette = baseVignette.copy(alpha = 0.025f),
            wash = baseWash.copy(alpha = 0.018f),
        )
        else -> vicoChartBackdropPalette(VicoChartAppearance.BACKDROP_THEME, scheme)
    }
}

/** Layered wash drawn behind the BG chart (`CartesianChartHost`) of the overview graphs. */
fun DrawScope.drawVicoChartBackdrop(palette: VicoChartBackdropPalette) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return
    drawRect(
        brush = Brush.verticalGradient(
            colorStops = arrayOf(
                0f to palette.top,
                0.38f to palette.mid,
                1f to palette.bottom,
            ),
            startY = 0f,
            endY = h,
        ),
        topLeft = Offset.Zero,
        size = Size(w, h),
    )
    drawRect(
        brush = Brush.verticalGradient(
            colorStops = arrayOf(
                0f to palette.vignette,
                0.42f to Color.Transparent,
                1f to Color.Transparent,
            ),
            startY = 0f,
            endY = h,
        ),
        topLeft = Offset.Zero,
        size = Size(w, h),
    )
    drawRect(
        brush = Brush.linearGradient(
            colorStops = arrayOf(
                0f to Color.Transparent,
                0.5f to palette.wash,
                1f to Color.Transparent,
            ),
            start = Offset.Zero,
            end = Offset(w, h),
        ),
        topLeft = Offset.Zero,
        size = Size(w, h),
    )
}
