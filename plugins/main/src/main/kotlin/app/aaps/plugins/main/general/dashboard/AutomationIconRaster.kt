package app.aaps.plugins.main.general.dashboard

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import app.aaps.core.interfaces.automation.AutomationIconData
import app.aaps.core.ui.compose.navigation.color
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Renders [AutomationIconData] (Compose [androidx.compose.ui.graphics.vector.ImageVector]) to a
 * classic [Drawable] for [com.google.android.material.button.MaterialButton] and other Views.
 *
 * Must run on the main thread: temporarily attaches a [ComposeView] to the activity content root.
 */
suspend fun rasterizeAutomationIconForViews(
    activity: Activity,
    data: AutomationIconData,
    iconSizeDp: Float = 24f,
): Drawable {
    val sizePx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        iconSizeDp,
        activity.resources.displayMetrics
    ).roundToInt().coerceAtLeast(24)
    return withContext(Dispatchers.Main.immediate) {
        suspendCoroutine { cont ->
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            val composeView = ComposeView(activity).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent {
                    MaterialTheme {
                        Icon(
                            imageVector = data.icon,
                            contentDescription = null,
                            modifier = Modifier.size(iconSizeDp.dp),
                            // Upstream removed AutomationIconData.tint in favour of ElementType theme colors.
                            tint = data.elementType?.color() ?: MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            val lp = ViewGroup.LayoutParams(sizePx, sizePx)
            root.addView(composeView, lp)
            composeView.visibility = View.INVISIBLE
            val listener = object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    composeView.viewTreeObserver.removeOnPreDrawListener(this)
                    Snapshot.sendApplyNotifications()
                    composeView.measure(
                        View.MeasureSpec.makeMeasureSpec(sizePx, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(sizePx, View.MeasureSpec.EXACTLY)
                    )
                    composeView.layout(0, 0, sizePx, sizePx)
                    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
                    val canvas = AndroidCanvas(bitmap)
                    composeView.draw(canvas)
                    root.removeView(composeView)
                    cont.resume(BitmapDrawable(activity.resources, bitmap))
                    return true
                }
            }
            composeView.viewTreeObserver.addOnPreDrawListener(listener)
            composeView.requestLayout()
        }
    }
}
