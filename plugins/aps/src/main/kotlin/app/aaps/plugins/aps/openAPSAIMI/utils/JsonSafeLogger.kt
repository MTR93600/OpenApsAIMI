package app.aaps.plugins.aps.openAPSAIMI.utils

import java.util.Locale

/**
 * 🛡️ JSON-safe string utilities for consoleLog
 * 
 * Ensures strings added to consoleLog won't break JSON serialization:
 * 1. Forces US locale for number formatting (no commas)
 * 2. Sanitizes potentially problematic characters
 * 3. Limits string length to prevent excessive JSON size
 */
object JsonSafeLogger {
    
    private const val MAX_LOG_LENGTH = 500  // Prevent excessive JSON size
    
    /**
     * Format a Double with US locale (decimal point, not comma)
     * Safe for JSON serialization
     */
    fun Double.formatUS(decimals: Int): String = 
        "%.${decimals}f".format(Locale.US, this)
    
    fun Float.formatUS(decimals: Int): String = 
        "%.${decimals}f".format(Locale.US, this)
    
    /**
     * Sanitize a string for safe JSON inclusion
     * - Removes control characters (U+0000 to U+001F except tab/newline)
     * - Escapes quotes and backslashes
     * - Truncates to max length
     * - Validates UTF-8
     */
    fun String.sanitizeForJson(): String {
        if (this.isEmpty()) return this
        
        return this
            // Remove control characters (except \t and \n which JSON allows)
            .replace(Regex("[\u0000-\u0008\u000B-\u000C\u000E-\u001F\u007F]"), "")
            // Escape backslashes first (to avoid double-escaping)
            .replace("\\", "\\\\")
            // Escape double quotes
            .replace("\"", "\\\"")
            // Truncate if too long
            .take(MAX_LOG_LENGTH)
    }
    
    /**
     * Safe alternative: Remove all non-ASCII characters (most conservative)
     * Use this if you want to be EXTRA safe and don't need emoji/unicode
     */
    fun String.toAsciiOnly(): String {
        return this.replace(Regex("[^\\x20-\\x7E]"), "")
    }
    
    /**
     * Validate that a string is valid UTF-8
     */
    fun String.isValidUtf8(): Boolean {
        return try {
            this.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) == this
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Safe logger: Sanitize before adding to consoleLog
     * Example: consoleLog.addSafe("DIA: ${dia.formatUS(2)}h")
     */
    fun MutableList<String>.addSafe(message: String) {
        val sanitized = message.sanitizeForJson()
        // Only add if valid UTF-8 and not empty
        if (sanitized.isNotEmpty() && sanitized.isValidUtf8()) {
            this.add(sanitized)
        }
    }
    
    /**
     * Safe logger with ASCII-only option (no emoji/unicode)
     * Example: consoleLog.addSafeAscii("DIA: ${dia.formatUS(2)}h")
     */
    fun MutableList<String>.addSafeAscii(message: String) {
        val asciiOnly = message.toAsciiOnly()
        if (asciiOnly.isNotEmpty()) {
            this.add(asciiOnly)
        }
    }
}
