package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag

/**
 * Logger for the learner file path. A failed read uses the same message the learner already
 * writes on Android (`Load failed, using defaults`). Those lines stay off the dose trace.
 */
internal class LearnerColdLogger : AAPSLogger {
    val failures = mutableListOf<String>()

    override fun debug(message: String) = Unit
    override fun debug(enable: Boolean, tag: LTag, message: String) = Unit
    override fun debug(tag: LTag, message: String) = Unit
    override fun debug(tag: LTag, accessor: () -> String) = Unit
    override fun debug(tag: LTag, format: String, vararg arguments: Any?) = Unit
    override fun warn(tag: LTag, message: String) {
        failures += message
    }
    override fun warn(tag: LTag, format: String, vararg arguments: Any?) {
        failures += format
    }
    override fun info(tag: LTag, message: String) = Unit
    override fun info(tag: LTag, format: String, vararg arguments: Any?) = Unit
    override fun error(tag: LTag, message: String) {
        failures += message
    }
    override fun error(tag: LTag, message: String, throwable: Throwable) {
        failures += message
    }
    override fun error(tag: LTag, format: String, vararg arguments: Any?) {
        failures += format
    }
    override fun error(message: String) {
        failures += message
    }
    override fun error(message: String, throwable: Throwable) {
        failures += message
    }
    override fun error(format: String, vararg arguments: Any?) {
        failures += format
    }
    override fun debug(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) = Unit
    override fun info(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) = Unit
    override fun warn(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) {
        failures += message
    }
    override fun error(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) {
        failures += message
    }
}
