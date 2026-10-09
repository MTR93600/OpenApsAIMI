package app.aaps.plugins.aps.openAPSAIMI.advisor.meal

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/**
 * Compresses a [Bitmap] to JPEG (quality 70, the value the Android providers always used)
 * and wraps the bytes for shared vision code.
 */
fun Bitmap.toAimiImage(): AimiImage {
    val bos = ByteArrayOutputStream()
    compress(Bitmap.CompressFormat.JPEG, 70, bos)
    return AimiImage(bos.toByteArray())
}
