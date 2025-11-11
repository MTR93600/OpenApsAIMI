package app.aaps.plugins.aps.openAPSAIMI.smb

import app.aaps.plugins.aps.openAPSAIMI.model.LoopContext

interface SmbEngine {
    data class Plan(val units: Double, val reason: String)
    fun planSmb(ctx: LoopContext, bypassDamping: Boolean): Plan
}
