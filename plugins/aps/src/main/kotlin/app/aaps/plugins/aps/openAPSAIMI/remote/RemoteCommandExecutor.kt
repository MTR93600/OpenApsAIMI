package app.aaps.plugins.aps.openAPSAIMI.remote

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventNSClientNewLog
import app.aaps.plugins.aps.openAPSAIMI.context.ContextManager
import app.aaps.core.interfaces.rx.events.EventRefreshOverview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RemoteCommandExecutor @Inject constructor(
    private val contextManager: ContextManager,
    private val securityValidator: SecurityValidator,
    private val rxBus: RxBus,
    private val aapsLogger: AAPSLogger
) {

    fun execute(command: ParsedCommand) {
        // 1. Validate PIN
        if (!securityValidator.validatePin(command.pin)) {
            log("Security Violation: Invalid PIN for command ${command.command}")
            return
        }

        // 2. Execute Command
        when (command.command) {
            RemoteCommandType.CONTEXT -> executeContext(command.args)
            RemoteCommandType.CANCEL  -> executeCancel()
            RemoteCommandType.STATUS  -> executeStatus()
            RemoteCommandType.SET     -> executeSet(command.args)
            RemoteCommandType.UNKNOWN -> log("Unknown command type")
        }
    }

    private fun executeContext(args: List<String>) {
        if (args.isEmpty()) {
            log("Context command missing arguments (e.g., 'SPORT 60')")
            return
        }
        
        // Join args to form user text, e.g. "SPORT 60" or "Meal 30min"
        val intentText = args.joinToString(" ")
        
        log("Executing Remote Context: '$intentText'")
        
        // Launch coroutine as addIntent is suspend
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Call ContextManager
                val ids = contextManager.addIntent(intentText, forceLLM = false)
                if (ids.isNotEmpty()) {
                    log("Context started via remote: $ids")
                } else {
                    log("Failed to start context (Parser returned no intents)")
                }
            } catch (e: Exception) {
                log("Error executing context: ${e.message}")
            }
        }
    }

    private fun executeCancel() {
        log("Executing Remote CANCEL: Clearing all contexts")
        contextManager.clearAll()
    }

    private fun executeStatus() {
        log("Executing Remote STATUS: Requesting update")
        // Force an update of the GUI/Status
        rxBus.send(EventRefreshOverview("AIMI Remote", true))
    }

    private fun executeSet(args: List<String>) {
        if (args.size < 2) {
            log("Set command requires key and value")
            return
        }
        val key = args[0]
        val value = args[1]

        if (!securityValidator.isSettingAllowed(key)) {
            log("Security Violation: Setting '$key' is not whitelisted for remote modification")
            return
        }

        // Implementation for setting modification would go here (Preferences.put)
        // For now, logged only as per analysis phase safe-guard
        log("Setting modification not fully implemented yet for key: $key")
    }

    private fun log(message: String) {
        aapsLogger.info(LTag.APS, "[Remote] $message")
        // Send to NSClient Logs so parent sees the response
        rxBus.send(EventNSClientNewLog("◄ REMOTE", message))
    }
}
