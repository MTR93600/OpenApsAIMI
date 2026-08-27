package app.aaps.plugins.aps.openAPSAIMI.wcycle

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences

class WCyclePreferences(private val p: Preferences) {
    fun enabled(): Boolean = p.get(BooleanKey.OApsAIMIwcycle)
    fun trackingMode(): CycleTrackingMode = enumValue(StringKey.OApsAIMIWCycleTrackingMode, CycleTrackingMode.FIXED_28)
    fun contraceptive(): ContraceptiveType = enumValue(StringKey.OApsAIMIWCycleContraceptive, ContraceptiveType.NONE)
    fun thyroid(): ThyroidStatus = enumValue(StringKey.OApsAIMIWCycleThyroid, ThyroidStatus.EUTHYROID)
    fun verneuil(): VerneuilStatus = enumValue(StringKey.OApsAIMIWCycleVerneuil, VerneuilStatus.NONE)
    fun startDom(): Int? = p.get(DoubleKey.OApsAIMIwcycledateday).toInt().takeIf { it in 1..31 }
    fun avgLen(): Int = p.get(IntKey.OApsAIMIWCycleAvgLength).coerceIn(24, 45)
    fun shadow(): Boolean = p.get(BooleanKey.OApsAIMIWCycleShadow)
    fun requireConfirm(): Boolean = p.get(BooleanKey.OApsAIMIWCycleRequireConfirm)
    fun clampMin(): Double = p.get(DoubleKey.OApsAIMIWCycleClampMin).coerceAtMost(1.0)
    fun clampMax(): Double = p.get(DoubleKey.OApsAIMIWCycleClampMax).coerceAtLeast(1.0)

    private inline fun <reified T : Enum<T>> enumValue(key: StringKey, default: T): T =
        runCatching { java.lang.Enum.valueOf(T::class.java, p.get(key).ifBlank { default.name }) }
            .getOrElse { default }
}
