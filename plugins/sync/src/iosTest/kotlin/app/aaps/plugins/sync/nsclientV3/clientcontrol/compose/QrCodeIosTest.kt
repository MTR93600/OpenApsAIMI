package app.aaps.plugins.sync.nsclientV3.clientcontrol.compose

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGColorSpaceCreateDeviceGray
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageRelease
import platform.CoreImage.CIDetector
import platform.CoreImage.CIDetectorAccuracy
import platform.CoreImage.CIDetectorAccuracyHigh
import platform.CoreImage.CIDetectorTypeQRCode
import platform.CoreImage.CIImage
import platform.CoreImage.CIQRCodeFeature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The QR code the iOS master app shows when it adds a client. It used to be no code at all, so
 * the QR way of pairing was missing on an iOS master.
 */
@OptIn(ExperimentalForeignApi::class)
class QrCodeIosTest {

    @Test
    fun `a code is made so the QR options are offered`() {
        assertTrue(qrCodeAvailable)
    }

    /** "AAPS" at level M is a version 1 code: 21 x 21 squares, the border cut off. */
    @Test
    fun `the code has the size of the QR version without the border`() {
        val modules = assertNotNull(qrModules("AAPS"))

        assertEquals(21, modules.size)
        // The three finder squares start in the corners, so the border really is gone.
        assertTrue(modules[0, 0])
        assertTrue(modules[20, 0])
        assertTrue(modules[0, 20])
        assertTrue(modules[6, 6])
        assertTrue(!modules[7, 7])
    }

    /** The real test: the iOS QR reader gets the same text back from the squares. */
    @Test
    fun `the iOS reader reads the text back`() {
        val text = "https://example.invalid/pair?token=client-0123456789abcdef&name=Test"
        val modules = assertNotNull(qrModules(text))

        assertEquals(text, decode(modules))
    }

    @Test
    fun `an empty border alone is no code`() {
        assertNull(trimBorder(BooleanArray(9), 3))
    }

    /** Draws [modules] like `QrCodeImage` (black on white, quiet zone of 4) and reads it with `CIDetector`. */
    private fun decode(modules: QrModules): String? {
        val scale = 8
        val quiet = 4
        val side = (modules.size + 2 * quiet) * scale
        return memScoped {
            val buffer = allocArray<UByteVar>(side * side)
            for (y in 0 until side) for (x in 0 until side) {
                val mx = x / scale - quiet
                val my = y / scale - quiet
                val dark = mx in 0 until modules.size && my in 0 until modules.size && modules[mx, my]
                buffer[y * side + x] = if (dark) 0u else 255u
            }
            val space = CGColorSpaceCreateDeviceGray()
            val context = CGBitmapContextCreate(buffer, side.toULong(), side.toULong(), 8u, side.toULong(), space, CGImageAlphaInfo.kCGImageAlphaNone.value)
            CGColorSpaceRelease(space)
            val cgImage = CGBitmapContextCreateImage(context)
            CGContextRelease(context)
            val detector = CIDetector.detectorOfType(CIDetectorTypeQRCode, context = null, options = mapOf(CIDetectorAccuracy to CIDetectorAccuracyHigh))
            val features = detector?.featuresInImage(CIImage.imageWithCGImage(cgImage))
            CGImageRelease(cgImage)
            (features?.firstOrNull() as? CIQRCodeFeature)?.messageString
        }
    }
}
