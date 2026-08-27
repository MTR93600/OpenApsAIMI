package app.aaps.implementation.stats

import app.aaps.core.interfaces.stats.TIR

class TirImpl(override val date: Long, override val lowThreshold: Double, override val highThreshold: Double) : TIR {

    override var below = 0
    override var inRange = 0
    override var above = 0
    override var error = 0
    override var count = 0

    override fun error() {
        error++
    }

    override fun below() {
        below++; count++
    }

    override fun inRange() {
        inRange++; count++
    }

    override fun above() {
        above++; count++
    }

    override fun belowPct() = if (count > 0) below.toDouble() / count * 100.0 else 0.0
    override fun inRangePct() = if (count > 0) 100 - belowPct() - abovePct() else 0.0
    override fun abovePct() = if (count > 0) above.toDouble() / count * 100.0 else 0.0
}
