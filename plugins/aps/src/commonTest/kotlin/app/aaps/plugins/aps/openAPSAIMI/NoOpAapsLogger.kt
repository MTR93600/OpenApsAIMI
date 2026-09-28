package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag

/**
 * An [AAPSLogger] that swallows every line.
 *
 * For shared tests of a class that has to be given a logger but whose log output is not what the
 * test is about. `commonTest` has no Mockito, so this is written out by hand once instead of once
 * per test file.
 */
internal object NoOpAapsLogger : AAPSLogger {

    override fun debug(message: String) = Unit
    override fun debug(enable: Boolean, tag: LTag, message: String) = Unit
    override fun debug(tag: LTag, message: String) = Unit
    override fun debug(tag: LTag, accessor: () -> String) = Unit
    override fun debug(tag: LTag, format: String, vararg arguments: Any?) = Unit
    override fun warn(tag: LTag, message: String) = Unit
    override fun warn(tag: LTag, format: String, vararg arguments: Any?) = Unit
    override fun info(tag: LTag, message: String) = Unit
    override fun info(tag: LTag, format: String, vararg arguments: Any?) = Unit
    override fun error(tag: LTag, message: String) = Unit
    override fun error(tag: LTag, message: String, throwable: Throwable) = Unit
    override fun error(tag: LTag, format: String, vararg arguments: Any?) = Unit
    override fun error(message: String) = Unit
    override fun error(message: String, throwable: Throwable) = Unit
    override fun error(format: String, vararg arguments: Any?) = Unit
    override fun debug(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) = Unit
    override fun info(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) = Unit
    override fun warn(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) = Unit
    override fun error(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) = Unit
}
