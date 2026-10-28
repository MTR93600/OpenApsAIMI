package app.aaps.core.ui.compose.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Battery icon of the Glass dashboard (pump battery age pill).
 *
 * replaces ic_glyco_battery (dev_OAPSAIMI, plugins/main). The path data is copied unchanged from the
 * drawable. The colour is black because every user tints it.
 *
 * @see IcGlycoBatteryIconPreview
 */
val IcGlycoBattery: ImageVector by lazy {
    ImageVector.Builder(
        name = "IcGlycoBattery",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        addPath(
            pathData = addPathNodes("M5,7 L5,17 C5,17.55 5.45,18 6,18 L17,18 C17.55,18 18,17.55 18,17 L18,7 C18,6.45 17.55,6 17,6 L6,6 C5.45,6 5,6.45 5,7z"),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
        addPath(
            pathData = addPathNodes("M18,10 L19.5,10 C19.78,10 20,10.22 20,10.5 L20,13.5 C20,13.78 19.78,14 19.5,14 L18,14"),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
        addPath(
            pathData = addPathNodes("M8,10 L8,14 L10,14 L10,10z"),
            fill = SolidColor(Color.Black),
            stroke = null,
        )
        addPath(
            pathData = addPathNodes("M11.5,10 L11.5,14 L13.5,14 L13.5,10z"),
            fill = SolidColor(Color.Black),
            stroke = null,
        )
    }.build()
}
