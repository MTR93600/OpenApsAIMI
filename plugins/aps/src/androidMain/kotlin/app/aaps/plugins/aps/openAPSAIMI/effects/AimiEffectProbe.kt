package app.aaps.plugins.aps.openAPSAIMI.effects

/**
 * Test-only trace of the Android shell. Production leaves [lines] unset, and then
 * [captureSetTbr] returns false so [setTempBasal] runs exactly as before.
 *
 * While a test is capturing, the requested temporary basal is recorded and the pump
 * body is skipped. The golden line is the decision's request, which is what the
 * shell will keep passing into `setTempBasal` after the decision moves to common code.
 */
internal object AimiEffectProbe {
    val lines = ThreadLocal<MutableList<String>?>()

    fun add(line: String) {
        lines.get()?.add(line)
    }

    fun isCapturing(): Boolean = lines.get() != null

    fun captureSetTbr(
        rateUph: Double,
        durationMin: Int,
        overrideSafetyLimits: Boolean,
        forceExact: Boolean,
        adaptiveMultiplier: Double,
    ): Boolean {
        if (!isCapturing()) return false
        add(
            AimiTickEffect.SetTbr(
                rateUph = rateUph,
                durationMin = durationMin,
                overrideSafetyLimits = overrideSafetyLimits,
                forceExact = forceExact,
                adaptiveMultiplier = adaptiveMultiplier,
            ).encode(),
        )
        return true
    }

    fun noteSmb(units: Double, owner: String) {
        if (!isCapturing()) return
        add(AimiTickEffect.Smb(units = units, owner = owner).encode())
    }
}
