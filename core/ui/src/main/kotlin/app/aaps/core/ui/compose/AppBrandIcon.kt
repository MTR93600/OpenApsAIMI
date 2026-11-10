package app.aaps.core.ui.compose

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import app.aaps.core.ui.R

/**
 * Drawer / about logo with a vector fallback when a mipmap launcher icon fails to load
 * (e.g. missing packaged PNG on device).
 */
@Composable
fun AppBrandIcon(
    @DrawableRes iconResId: Int,
    modifier: Modifier = Modifier,
    contentDescription: String? = null
) {
    val appContext = LocalContext.current.applicationContext
    val fallbackPainter = painterResource(R.drawable.ic_notif_aaps)
    val primaryPainter = remember(iconResId) {
        runCatching {
            val drawable = ContextCompat.getDrawable(appContext, iconResId) ?: return@runCatching null
            BitmapPainter(drawable.toBitmap().asImageBitmap())
        }.getOrNull()
    }
    Image(
        painter = primaryPainter ?: fallbackPainter,
        contentDescription = contentDescription,
        modifier = modifier
    )
}
