package app.aaps.wear.complications

import app.aaps.wear.R
import app.aaps.wear.watchfaces.CustomWatchface

/**
 * The whole Custom watch face as one image.
 *
 * For a watch face that wants the complete picture in a single slot. The Watch Face Format document
 * AAPS pushes uses the two halves instead ([CwfLowerComplication] and [CwfUpperComplication]), so
 * each can refresh at its own rate, but this one stays available for any other face with an image
 * slot.
 */
class CwfImageComplication : CwfBlockComplication() {

    override val block = CustomWatchface.RenderBlock.ALL
    override val label = R.string.complication_cwf_image
    override fun getProviderCanonicalName(): String = CwfImageComplication::class.java.canonicalName!!
}
