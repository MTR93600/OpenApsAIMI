package app.aaps.core.ui.compose.dashboard

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import app.aaps.core.interfaces.overview.graph.BgType

/**
 * Distinct theme colors for source-typed prediction paths.
 *
 * A point's type is preserved from Overview data, and each type receives its own path and matching
 * legend swatch. Null is reserved for legacy untyped single-series callers.
 */
internal fun dashboardPredictionSeriesColor(type: BgType?, scheme: ColorScheme): Color = when (type) {
    BgType.IOB_PREDICTION -> scheme.secondary
    BgType.COB_PREDICTION -> scheme.tertiary
    BgType.A_COB_PREDICTION -> scheme.tertiaryContainer
    BgType.UAM_PREDICTION -> scheme.error
    BgType.ZT_PREDICTION -> scheme.outline
    null -> scheme.tertiary
    BgType.REGULAR, BgType.BUCKETED -> scheme.tertiary
}
