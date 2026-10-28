package app.aaps.core.ui.compose.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Gear icon of the Glass dashboard (pump and loop pills).
 *
 * replaces ic_glyco_settings (dev_OAPSAIMI, plugins/main). The path data is copied unchanged from the
 * drawable. The colour is black because every user tints it.
 *
 * @see IcGlycoSettingsIconPreview
 */
val IcGlycoSettings: ImageVector by lazy {
    ImageVector.Builder(
        name = "IcGlycoSettings",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        addPath(
            pathData = addPathNodes("M12,12 m-3,0 a3,3 0,1 1,6 0 a3,3 0,1 1,-6 0"),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
        addPath(
            pathData = addPathNodes("M19.4,15 a1.65,1.65 0,0 0,0.33 1.82l0.06,0.06 a2,2 0,0 1,0 2.83,2 2,0 0 1,-2.83 0l-0.06,-0.06 a1.65,1.65 0,0 0,-1.82 -0.33,1.65 1.65,0 0 0,-1 1.51 V21 a2,2 0,0 1,-2 2,2 2,0 0 1,-2 -2v-0.09 A1.65,1.65 0,0 0,9 19.4a1.65,1.65 0,0 0,-1.82 0.33l-0.06,0.06 a2,2 0,0 1,-2.83 0,2 2,0 0 1,0 -2.83l0.06,-0.06 a1.65,1.65 0,0 0,0.33 -1.82,1.65 1.65,0 0 0,-1.51 -1H3 a2,2 0,0 1,-2 -2,2 2,0 0 1,2 -2h0.09 A1.65,1.65 0,0 0,4.6 9a1.65,1.65 0,0 0,-0.33 -1.82l-0.06,-0.06 a2,2 0,0 1,0 -2.83,2 2,0 0 1,2.83 0l0.06,0.06 a1.65,1.65 0,0 0,1.82 0.33H9 a1.65,1.65 0,0 0,1 -1.51V3 a2,2 0,0 1,2 -2,2 2,0 0 1,2 2v0.09 a1.65,1.65 0,0 0,1 1.51,1.65 1.65,0 0 0,1.82 -0.33l0.06,-0.06 a2,2 0,0 1,2.83 0,2 2,0 0 1,0 2.83l-0.06,0.06 a1.65,1.65 0,0 0,-0.33 1.82V9 a1.65,1.65 0,0 0,1.51 1H21 a2,2 0,0 1,2 2,2 2,0 0 1,-2 2h-0.09 a1.65,1.65 0,0 0,-1.51 1z"),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }.build()
}
