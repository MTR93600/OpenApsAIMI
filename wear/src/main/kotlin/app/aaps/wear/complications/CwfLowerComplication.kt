package app.aaps.wear.complications

import app.aaps.wear.R
import app.aaps.wear.watchfaces.CustomWatchface

/**
 * The lower half of the Custom watch face: background, images and the chart.
 *
 * The slow half - it holds what a heavy zip makes expensive and what changes least often, so it can
 * be refreshed at data rate while [CwfUpperComplication] keeps up with the clock.
 */
class CwfLowerComplication : CwfBlockComplication() {

    override val block = CustomWatchface.RenderBlock.LOWER
    override val label = R.string.complication_cwf_lower
    override fun getProviderCanonicalName(): String = CwfLowerComplication::class.java.canonicalName!!
}
