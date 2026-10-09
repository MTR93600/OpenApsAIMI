package app.aaps.plugins.aps.openAPSAIMI.advisor.meal

import kotlin.jvm.JvmInline

/**
 * A food photo ready for a vision model, as JPEG bytes.
 *
 * The platform compresses the captured [android.graphics.Bitmap][android.graphics.Bitmap] (or the
 * iOS `UIImage`) to JPEG and hands the bytes over; shared code never names a platform image type.
 * Android: `Bitmap.toAimiImage()`. iOS: `UIImage.toAimiImage()`.
 */
@JvmInline
value class AimiImage(val jpegBytes: ByteArray) {

    /** Base64 of the JPEG bytes, for `data:image/jpeg;base64,...` payloads. */
    fun base64(): String = kotlin.io.encoding.Base64.encode(jpegBytes)
}
