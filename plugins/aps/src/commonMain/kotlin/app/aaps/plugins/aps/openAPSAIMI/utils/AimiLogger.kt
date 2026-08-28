package app.aaps.plugins.aps.openAPSAIMI.utils

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.utils.JsonSafeLogger.sanitizeForJson
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.measureTimedValue

/**
 * 🎯 AimiLogger: Structured Logging DSL for AI decision traceability.
 *
 * This logger provides a type-safe way to log AI decisions with metadata,
 * ensuring that logs are structured and safe for long-term storage and analysis.
 */
@SingleIn(AppScope::class)
class AimiLogger @Inject constructor(private val aapsLogger: AAPSLogger) {

    enum class Level { DEBUG, INFO, WARN, ERROR }

    /**
     * 🧩 Log Event Context
     */
    class LogEventBuilder {
        var context: String = "general"
        var operation: String = "unknown"
        var message: String = ""
        private val metadata = mutableMapOf<String, String>()

        fun tag(key: String, value: Any?) {
            metadata[key] = value?.toString()?.sanitizeForJson() ?: "null"
        }

        fun build(): String {
            val sb = StringBuilder()
            sb.append("[$context:$operation] $message")
            if (metadata.isNotEmpty()) {
                sb.append(" {")
                val metaString = metadata.entries.joinToString(", ") { "${it.key}=${it.value}" }
                sb.append(metaString)
                sb.append("}")
            }
            return sb.toString()
        }
    }

    /**
     * Log an INFO event using DSL
     */
    fun info(init: LogEventBuilder.() -> Unit) = log(Level.INFO, init)

    /**
     * Log a DEBUG event using DSL
     */
    fun debug(init: LogEventBuilder.() -> Unit) = log(Level.DEBUG, init)

    /**
     * Log a WARN event using DSL
     */
    fun warn(init: LogEventBuilder.() -> Unit) = log(Level.WARN, init)

    /**
     * Log an ERROR event using DSL
     */
    fun error(init: LogEventBuilder.() -> Unit) = log(Level.ERROR, init)

    /**
     * ⏱️ Measure and log performance of a block
     */
    fun <T> measure(operationName: String, block: () -> T): T {
        val timed = measureTimedValue(block)
        info {
            context = "performance"
            operation = operationName
            message = "Execution completed"
            tag("duration_ms", timed.duration.inWholeMilliseconds)
        }
        return timed.value
    }

    private fun log(level: Level, init: LogEventBuilder.() -> Unit) {
        val builder = LogEventBuilder()
        builder.init()
        val formattedMessage = "🤖 ${builder.build()}"

        when (level) {
            Level.DEBUG -> aapsLogger.debug(LTag.AIMI, formattedMessage)
            Level.INFO -> aapsLogger.info(LTag.AIMI, formattedMessage)
            Level.WARN -> aapsLogger.warn(LTag.AIMI, formattedMessage)
            Level.ERROR -> aapsLogger.error(LTag.AIMI, formattedMessage)
        }
    }
}
