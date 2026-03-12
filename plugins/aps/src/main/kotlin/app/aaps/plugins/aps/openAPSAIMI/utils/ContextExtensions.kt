package app.aaps.plugins.aps.openAPSAIMI.utils

import app.aaps.plugins.aps.openAPSAIMI.model.BgSnapshot
import app.aaps.plugins.aps.openAPSAIMI.model.LoopContext
import app.aaps.plugins.aps.openAPSAIMI.model.SmbPlan
import app.aaps.plugins.aps.openAPSAIMI.model.BasalPlan

/**
 * Kotlin Extensions for core context types.
 * Encapsulates domain logic for validation, prediction and formatting.
 */

// --- BG Extensions ---

fun BgSnapshot.isReliable(): Boolean = mgdl in 39.0..401.0 && delta5 != 0.0

fun BgSnapshot.toShortString(): String = "BG: ${mgdl.toInt()} (Δ${"%.1f".format(delta5)})"

// --- LoopContext Extensions ---

fun LoopContext.isHypoRisk(): Boolean = bg.mgdl < profile.targetMgdl - 20.0 || eventualBg < 70.0

fun LoopContext.getRemainingInsulinU(): Double = iobU.coerceAtLeast(0.0)

// --- Decision Strategy Interfaces ---

interface DecisionStrategy {
    fun calculateBasal(context: LoopContext): BasalPlan?
    fun calculateSmb(context: LoopContext): SmbPlan?
}

interface PredictionStrategy {
    fun predict(context: LoopContext, minutes: Int): List<Double>
}

/**
 * Validator extension for robust context checking.
 */
object ContextValidator {
    @JvmStatic
    fun validate(context: LoopContext): Boolean {
        return context.bg.mgdl > 0 && context.profile.isfMgdlPerU > 0
    }
}

/**
 * Serializer for context data, supporting JSON-like or structured logging.
 */
object ContextSerializer {
    @JvmStatic
    fun serialize(context: LoopContext): String {
        return "LoopContext(bg=${context.bg.mgdl}, iob=${context.iobU}, cob=${context.cobG})"
    }
}

/**
 * Interface for custom context processing plugins.
 */
interface ContextPlugin {
    val pluginId: String
    fun process(context: LoopContext): LoopContext
}

/**
 * Registry for context plugins.
 */
object ContextPluginRegistry {
    private val plugins = mutableListOf<ContextPlugin>()

    @JvmStatic
    fun register(plugin: ContextPlugin) {
        plugins.add(plugin)
    }

    @JvmStatic
    fun processAll(context: LoopContext): LoopContext {
        var currentContext = context
        for (plugin in plugins) {
            currentContext = plugin.process(currentContext)
        }
        return currentContext
    }
}
