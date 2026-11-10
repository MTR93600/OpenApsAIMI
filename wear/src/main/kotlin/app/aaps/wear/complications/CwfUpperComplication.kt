package app.aaps.wear.complications

import app.aaps.wear.R
import app.aaps.wear.watchfaces.CustomWatchface

/**
 * The upper half of the Custom watch face: clock, date fields, cover plate and hands.
 *
 * Transparent wherever its own views do not paint, so [CwfLowerComplication] shows through beneath
 * it. The fast half - its cost stays in a narrow band whatever the zip weighs, which is what makes a
 * per-second refresh affordable.
 */
class CwfUpperComplication : CwfBlockComplication() {

    override val block = CustomWatchface.RenderBlock.UPPER
    override val label = R.string.complication_cwf_upper
    override fun getProviderCanonicalName(): String = CwfUpperComplication::class.java.canonicalName!!
}
