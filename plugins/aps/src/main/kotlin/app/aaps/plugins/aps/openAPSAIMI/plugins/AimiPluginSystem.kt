package app.aaps.plugins.aps.openAPSAIMI.plugins

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.model.AimiAction
import app.aaps.plugins.aps.openAPSAIMI.model.AimiPluginContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 🛠️ AimiDecisionPlugin
 * Contract for all AIMI decision-making extensions.
 */
interface AimiDecisionPlugin {
    val id: String
    val name: String
    val priority: Int // Higher value = Higher priority (0-100)

    /**
     * Analyze current context and propose medical actions.
     */
    fun analyze(context: AimiPluginContext): List<AimiAction>

    /**
     * Resolve conflicts between this plugin and another proposed action.
     * @return The winning action or null to delegate to the next plugin.
     */
    fun resolveConflict(myAction: AimiAction, otherAction: AimiAction): AimiAction? = null
}

/**
 * 🏗️ AimiPluginManager
 * Orchestrates the registration, prioritization, and execution of AI plugins.
 */
@Singleton
class AimiPluginManager @Inject constructor(
    private val aapsLogger: AAPSLogger
) {
    private val plugins = mutableListOf<AimiDecisionPlugin>()

    /**
     * Register a new plugin. Automatically sorted by priority.
     */
    fun register(plugin: AimiDecisionPlugin) {
        if (plugins.any { it.id == plugin.id }) {
            aapsLogger.warn(LTag.APS, "Plugin ${plugin.id} already registered. Skipping.")
            return
        }
        plugins.add(plugin)
        plugins.sortByDescending { it.priority }
        aapsLogger.info(LTag.APS, "🧩 AimiPluginManager: Registered ${plugin.name} (Priority: ${plugin.priority})")
    }

    /**
     * Unregister a plugin by ID.
     */
    fun unregister(id: String) {
        plugins.removeIf { it.id == id }
    }

    /**
     * Execute all registered plugins and collect actions.
     */
    fun collectActions(context: AimiPluginContext): List<AimiAction> {
        val allActions = mutableListOf<AimiAction>()
        
        plugins.forEach { plugin ->
            try {
                val proposed = plugin.analyze(context)
                if (proposed.isNotEmpty()) {
                    aapsLogger.debug(LTag.APS, "🧩 Plugin ${plugin.id} proposed ${proposed.size} actions")
                    
                    // Basic conflict resolution: If we already have actions of the same type,
                    // let the specific plugin resolve it or default to priority.
                    proposed.forEach { action ->
                        mergeOrAdd(allActions, action, plugin)
                    }
                }
            } catch (e: Exception) {
                aapsLogger.error(LTag.APS, "🧩 Plugin ${plugin.id} failed during analysis", e)
            }
        }
        
        return allActions
    }

    private fun mergeOrAdd(currentActions: MutableList<AimiAction>, newAction: AimiAction, source: AimiDecisionPlugin) {
        // Simple strategy: If multiple plugins propose the same Action type (e.g. TemporaryBasal),
        // we keep the one with the highest priority already in the list, unless source overrides.
        // For simplicity in Phase 1, we collect all and let the Auditor/AutoDrive handle final arbitration.
        currentActions.add(newAction)
    }

    fun getPlugins(): List<AimiDecisionPlugin> = plugins.toList()
}
