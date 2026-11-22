package app.aaps.plugins.sync.nsclientV3.clientcontrol.compose

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceGray
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreImage.CIContext
import platform.CoreImage.CIFilter
import platform.CoreImage.createCGImage
import platform.CoreImage.filterWithName
import platform.CoreImage.kCIContextUseSoftwareRenderer
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.setValue

/**
 * The QR code from Core Image's `CIQRCodeGenerator`, which is part of iOS: no library to add.
 *
 * The master app shows this code when it adds a client. This used to return null, on the grounds
 * that iOS ran only as a follower and a follower never shows a code. The iOS app has been a master
 * since 2026-10-22, so a master on iOS could not offer the QR way of pairing at all.
 *
 * The same error correction level as the ZXing version on Android and the desktop (M), so a code
 * holds the same text on every platform. Core Image gives one pixel per square, with an empty
 * border of its own; the border is cut off here, because `QrCodeImage` draws its own.
 *
 * Returns null if Core Image cannot make the code (text too long, filter missing), which hides the
 * QR options, as before.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun qrModules(text: String): QrModules? {
    @Suppress("CAST_NEVER_SUCCEEDS")
    val data = (text as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return null
    val filter = CIFilter.filterWithName("CIQRCodeGenerator") ?: return null
    filter.setValue(data, forKey = "inputMessage")
    filter.setValue(NSString.create(string = "M"), forKey = "inputCorrectionLevel")
    val image = filter.outputImage ?: return null
    // The software renderer: the image is 23 x 23 pixels, so the GPU gains nothing, and a context
    // without a GPU also works where there is none (the test simulator could not make the image).
    val context = CIContext.contextWithOptions(mapOf<Any?, Any?>(kCIContextUseSoftwareRenderer to true))
    val cgImage = context.createCGImage(image, fromRect = image.extent) ?: return null
    try {
        val width = CGImageGetWidth(cgImage).toInt()
        val height = CGImageGetHeight(cgImage).toInt()
        if (width <= 0 || width != height) return null
        val pixels = memScoped {
            val buffer = allocArray<UByteVar>(width * height)
            val space = CGColorSpaceCreateDeviceGray()
            val context = CGBitmapContextCreate(
                data = buffer,
                width = width.toULong(),
                height = height.toULong(),
                bitsPerComponent = 8u,
                bytesPerRow = width.toULong(),
                space = space,
                bitmapInfo = CGImageAlphaInfo.kCGImageAlphaNone.value
            )
            CGColorSpaceRelease(space)
            if (context == null) return null
            CGContextDrawImage(context, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()), cgImage)
            CGContextRelease(context)
            // Row 0 of the buffer is the top row of the picture, as in the image itself.
            BooleanArray(width * height) { buffer[it].toInt() < DARK_BELOW }
        }
        return trimBorder(pixels, width)
    } finally {
        CGImageRelease(cgImage)
    }
}

/** Grey values below this are a dark square. Core Image draws pure black and pure white. */
private const val DARK_BELOW = 128

/**
 * Cuts the empty border Core Image puts around the code. The code is square and its corners are
 * dark finder squares, so the first and last dark pixel in each direction bound it exactly.
 */
internal fun trimBorder(pixels: BooleanArray, width: Int): QrModules? {
    var minX = width
    var minY = width
    var maxX = -1
    var maxY = -1
    for (y in 0 until width) for (x in 0 until width) {
        if (!pixels[y * width + x]) continue
        if (x < minX) minX = x
        if (x > maxX) maxX = x
        if (y < minY) minY = y
        if (y > maxY) maxY = y
    }
    if (maxX < 0) return null
    val size = maxX - minX + 1
    if (maxY - minY + 1 != size) return null
    return QrModules(size, BooleanArray(size * size) { pixels[(minY + it / size) * width + minX + it % size] })
}
