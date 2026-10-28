package app.aaps.core.ui.compose.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Drop shaped insulin icon of the Glass dashboard (insulin age pill).
 *
 * replaces ic_glyco_insulin (dev_OAPSAIMI, plugins/main). The path data is copied unchanged from the
 * drawable. The colour is black because every user tints it.
 *
 * @see IcGlycoInsulinIconPreview
 */
val IcGlycoInsulin: ImageVector by lazy {
    ImageVector.Builder(
        name = "IcGlycoInsulin",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        addPath(
            pathData = addPathNodes("M12,2.69l5.66,5.66a8,8 0,1 1,-11.31 0z"),
            fill = SolidColor(Color.Black),
            stroke = null,
        )
    }.build()
}
