package app.aaps.wear.complications

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.annotation.StringRes
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PhotoImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import app.aaps.core.interfaces.logging.LTag
import app.aaps.wear.R
import app.aaps.wear.watchfaces.CustomWatchface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import app.aaps.wear.data.ComplicationData as ComplicationStore

/**
 * Publishes part of the Custom watch face as an image complication.
 *
 * This is the bridge for watches whose firmware no longer selects or binds code-based watch faces
 * (Galaxy Watch 7 and later). There a Watch Face Format document is the only face that can run, and
 * a complication is the only channel from AAPS into it. So the CWF keeps being drawn by our own
 * code - honouring the user's zip, its dynamic values and its `dynPref` cascade - and the result is
 * handed over as a picture for the document to display.
 *
 * Subclasses differ only in [block]. Splitting the face lets the two halves refresh at different
 * rates: the lower half carries the background, the images and the chart and changes slowly, while
 * the upper half carries the clock and the hands and has to keep up with the second. Measurements
 * behind that split are in `_docs/CWF_WFF_Prompt.md`.
 *
 * Limits inherent to the approach, documented there rather than worked around here: the picture only
 * changes when the system asks for an update, and the user's own complication slots are not part of
 * it, because complication data only ever reaches the watch face the system has bound.
 */
abstract class CwfBlockComplication : ModernBaseComplicationProviderService() {

    companion object {

        /**
         * One watch face shared by every subclass, kept for the life of the process rather than of a
         * service instance: the system binds a data source only for as long as it takes to answer,
         * so an instance field would be discarded between requests and every render would pay full
         * construction again - about 275 ms against about 90 ms warm, since construction, injection
         * and inflation dominate.
         *
         * Shared rather than one per provider because the halves are two views of the *same* watch
         * face; separate instances would triple the memory and re-inflate the same layout.
         *
         * Holding it is safe: `prepareForRendering` attaches the **application** context, so nothing
         * belonging to a single bind is retained.
         */
        private var warmWatchFace: CustomWatchface? = null

        /**
         * The loaded zip's own picture, cached from the last render.
         *
         * Refreshed on every render, so it follows a newly sent zip, and read on the binder thread by
         * [getPreviewData] - which must not touch the repository or build a watch face.
         */
        @Volatile private var previewBytes: ByteArray? = null

        /** Starting size for the compression buffer - only avoids a few reallocations. */
        private const val BITMAP_COMPRESS_HINT_BYTES = 64 * 1024

        /**
         * Whether the face currently being drawn shows seconds at all.
         *
         * Both the zip and the user have a say - `enableSecond` is the json's `enableSecond` **and**
         * the "show seconds" preference - so this cannot be answered from the preference alone. Used
         * to decide how often the upper half is worth refreshing: a design without seconds gains
         * nothing from a per-second render and would only cost battery.
         *
         * False until something has been drawn, which errs towards the cheap rate.
         */
        internal fun showsSeconds(): Boolean = warmWatchFace?.enableSecond == true

        /**
         * True when the watch is in its low-power always-on state rather than active.
         *
         * A complication data source is never told about ambient mode - nothing in the request says
         * so - but it can look at the display, which reports a doze state while the watch is in
         * ambient. That is what lets the picture hide its seconds and drop to the cheap refresh rate
         * without the watch face having to tell us.
         */
        internal fun isAmbient(context: Context): Boolean =
            (context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.state
                ?: Display.STATE_ON) != Display.STATE_ON
    }

    /** Runs the request. Main by default because [render] must own the view hierarchy. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Which half of the face this provider draws. */
    protected abstract val block: CustomWatchface.RenderBlock

    /** Label shown in the complication picker and used as the content description. */
    @get:StringRes
    protected abstract val label: Int

    override fun buildComplicationData(
        type: ComplicationType,
        data: ComplicationStore,
        complicationPendingIntent: PendingIntent
    ): ComplicationData? = buildFromIcon(type, encode(render()), complicationPendingIntent)

    /** Wraps an already rendered frame in the complication type the slot asked for. */
    private fun buildFromIcon(type: ComplicationType, icon: Icon, tapIntent: PendingIntent): ComplicationData? {
        val contentDescription = PlainComplicationText.Builder(text = getString(label)).build()

        return when (type) {
            ComplicationType.PHOTO_IMAGE -> PhotoImageComplicationData.Builder(
                photoImage = icon,
                contentDescription = contentDescription
            )
                .setTapAction(tapIntent)
                .build()

            ComplicationType.SMALL_IMAGE -> SmallImageComplicationData.Builder(
                smallImage = SmallImage.Builder(image = icon, type = SmallImageType.PHOTO).build(),
                contentDescription = contentDescription
            )
                .setTapAction(tapIntent)
                .build()

            else                         -> {
                aapsLogger.warn(LTag.WEAR, "${javaClass.simpleName} unexpected type: $type")
                null
            }
        }
    }

    /**
     * Draws [block] at screen size.
     *
     * A complication request carries no size, so the size is our choice; the screen is the right one
     * because this image is meant to fill the face, and the document scales it to the rectangle it
     * declares.
     *
     * Runs on the caller's thread, which for the live path is the main thread
     * ([ModernBaseComplicationProviderService] uses `Dispatchers.Main.immediate`) - required, both
     * because this inflates a view hierarchy and because the same thread must touch those views
     * every time.
     *
     * `refreshRenderData()` is what keeps a warm instance honest: it reloads the data, re-applies it,
     * and reaches the watch face's own re-read of its description, so a newly sent zip is picked up
     * without discarding anything.
     */
    /**
     * Answers a request with the render on the main thread and the encode off it.
     *
     * The base class does the whole of this inside `Dispatchers.Main.immediate`, which is right for
     * a text complication but expensive here: measured on a Galaxy Watch 4, the render takes about
     * 225 ms and the PNG encode a further 120 to 150 ms, and the upper half runs **every second**
     * while seconds are shown. Leaving both on the main thread spent roughly a third of every second
     * there, which showed up as a watch that felt saturated and a second hand that lost its grid
     * whenever a tick overran.
     *
     * The render has to stay on the main thread - it inflates and draws a view hierarchy, and the
     * same thread must own those views every time - but the encode only reads pixels, so it moves to
     * a background dispatcher.
     *
     * Overriding the whole request also skips the base class's `DataStore` read, which this path
     * never used: the watch face reloads its own data in `refreshRenderData()`.
     */
    override fun onComplicationRequest(request: ComplicationRequest, listener: ComplicationRequestListener) {
        val tapIntent = ComplicationTapActivity.getTapActionIntent(
            context = this,
            provider = ComponentName(this, getProviderCanonicalName()),
            complicationId = request.complicationInstanceId,
            action = getComplicationAction()
        )
        scope.launch {
            val data = try {
                val bitmap = render()
                val icon = withContext(Dispatchers.Default) { encode(bitmap) }
                buildFromIcon(request.complicationType, icon, tapIntent)
            } catch (t: Throwable) {
                // Throwable, not Exception, on purpose. This draws through androidx.wear.watchface,
                // and the manifest declares `wear-sdk` as an optional shared library. On a watch
                // where it is missing, touching a class that needs it raises NoClassDefFoundError -
                // an Error, which a `catch (e: Exception)` lets straight through. The coroutine
                // would then die without ever answering the listener, the slot would stay empty for
                // good, and nothing would be logged: a black watch face with no clue why. Reported
                // from a Galaxy Watch 7, the hardware none of the developers can test on.
                //
                // The class name is logged because that is what names the missing piece.
                aapsLogger.error(
                    LTag.WEAR,
                    "${javaClass.simpleName}: render failed (${t.javaClass.simpleName}: ${t.message})",
                    t
                )
                null
            }
            listener.onComplicationData(data)
        }
    }

    /**
     * Compresses the rendered frame.
     *
     * A raw 450x450 bitmap is about 791 kB, and two of them travel to the system for every refresh.
     * That approaches the Binder transaction limit on each call, and the wear process was measured
     * being killed every few seconds while visible and holding a foreground service until this was
     * added; see `_docs/CWF_WFF_Prompt.md` §7w.
     *
     * Lossless on purpose: the picture is flat colour, text and thin hands, which a lossy codec
     * would smear at exactly the sizes that matter.
     *
     * Safe off the main thread - it only reads pixels - and that is where [onComplicationRequest]
     * runs it.
     */
    private fun encode(bitmap: Bitmap): Icon {
        val started = System.currentTimeMillis()
        val stream = ByteArrayOutputStream(BITMAP_COMPRESS_HINT_BYTES)
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        val bytes = stream.toByteArray()
        aapsLogger.debug(
            LTag.WEAR,
            "${javaClass.simpleName}: $block image ${bytes.size / 1024} kB compressed in ${System.currentTimeMillis() - started} ms," +
                " was ${bitmap.byteCount / 1024} kB raw"
        )
        bitmap.recycle()
        return Icon.createWithData(bytes, 0, bytes.size)
    }

    private fun render(): Bitmap {
        val metrics = resources.displayMetrics
        val started = System.currentTimeMillis()
        val watchFace = warmWatchFace ?: CustomWatchface().also {
            it.prepareForRendering(this)
            warmWatchFace = it
        }
        // The picture is a still frame: a second hand it cannot keep moving would freeze at a stale
        // position, so the watch face is told to draw as ambient and drop its seconds.
        watchFace.setRenderAmbient(isAmbient(this))
        watchFace.refreshRenderData()
        val bitmap = watchFace.renderBlock(metrics.widthPixels, metrics.heightPixels, block)
        // Captured here, on the main thread, so the preview path never has to load anything
        previewBytes = watchFace.previewImageBytes()
        aapsLogger.debug(
            LTag.WEAR,
            "${javaClass.simpleName}: rendered $block ${bitmap.width}x${bitmap.height} in ${System.currentTimeMillis() - started} ms" +
                " (ambient=${isAmbient(this)} enableSecond=${watchFace.enableSecond})"
        )
        return bitmap
    }

    /**
     * A **still** picture of the watch face, never a live render.
     *
     * Two reasons the render path cannot be used, either sufficient. The base class builds preview
     * data on the binder thread, and inflating a view hierarchy off the main thread is not allowed.
     * And a preview is requested whenever anyone browses a complication picker - on a watch still
     * running the code-based Custom watch face, rendering there would build a second
     * `CustomWatchface` beside the live one, and the two share process-wide state.
     *
     * The androidx contract asks for a fixed preview anyway: `getPreviewData` should show
     * representative content, not live data.
     *
     * It uses **the loaded zip's own picture** rather than the built-in default, because this is what
     * the watch face editor renders when the user long-presses the face - showing an unrelated design
     * there is confusing. Falls back to the built-in image when no zip has been drawn yet.
     */
    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val contentDescription = PlainComplicationText.Builder(text = getString(label)).build()
        val icon = previewBytes?.let { Icon.createWithData(it, 0, it.size) }
            ?: Icon.createWithResource(this, R.drawable.watchface_custom)

        return when (type) {
            ComplicationType.PHOTO_IMAGE -> PhotoImageComplicationData.Builder(
                photoImage = icon,
                contentDescription = contentDescription
            ).build()

            ComplicationType.SMALL_IMAGE -> SmallImageComplicationData.Builder(
                smallImage = SmallImage.Builder(image = icon, type = SmallImageType.PHOTO).build(),
                contentDescription = contentDescription
            ).build()

            else                         -> null
        }
    }

    /** A tap lands anywhere on a full-screen picture, so it opens the menu rather than one detail screen. */
    override fun getComplicationAction(): ComplicationAction = ComplicationAction.AIMI_EATING
}
