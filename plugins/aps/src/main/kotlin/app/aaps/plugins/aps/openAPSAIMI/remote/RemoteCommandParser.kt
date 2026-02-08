package app.aaps.plugins.aps.openAPSAIMI.remote

import javax.inject.Inject
import javax.inject.Singleton

data class ParsedCommand(
    val pin: String,
    val command: RemoteCommandType,
    val args: List<String>
)

enum class RemoteCommandType {
    CONTEXT, // Start context
    CANCEL,  // Stop all contexts
    SET,     // Change setting
    STATUS,  // Force status update
    UNKNOWN
}

@Singleton
class RemoteCommandParser @Inject constructor() {

    private val PREFIX = "AIMI:"

    fun parse(text: String): ParsedCommand? {
        if (!text.startsWith(PREFIX, ignoreCase = true)) return null

        // Remove prefix and trim
        val cleanText = text.substring(PREFIX.length).trim()
        
        // Expected format: <PIN> <CMD> [ARGS...]
        // Split by whitespace
        val parts = cleanText.split("\\s+".toRegex()).filter { it.isNotBlank() }
        
        if (parts.size < 2) return null // Need at least PIN and CMD

        val pin = parts[0]
        val cmdString = parts[1].uppercase()
        val args = parts.drop(2)

        val commandType = try {
            RemoteCommandType.valueOf(cmdString)
        } catch (e: IllegalArgumentException) {
            RemoteCommandType.UNKNOWN
        }

        return ParsedCommand(pin, commandType, args)
    }
}
