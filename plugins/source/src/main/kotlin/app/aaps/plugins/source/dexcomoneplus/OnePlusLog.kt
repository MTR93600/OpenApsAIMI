package app.aaps.plugins.source.dexcomoneplus

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Where this driver writes what it is doing.
 *
 * It goes through slf4j rather than `android.util.Log` for one reason: AAPS already writes
 * everything that comes through slf4j to `AndroidAPS.log`, a rolling file with months of history,
 * and **Maintenance, Send logs** already collects those files. A driver that logged straight to
 * Android would be invisible to that: its lines would live only in the phone's own ring buffer,
 * which holds minutes on a busy phone and which an app may not even be allowed to read back.
 *
 * The line still reaches the phone's log as well, under the same tag as before, so
 * `adb logcat | grep DEXCOM_ONEPLUS` keeps working while a developer is watching.
 *
 * Note that this writes whatever it is given, without asking the AAPS log settings. That is on
 * purpose for a plugin behind an engineering switch: the whole point of these lines is to exist
 * when something fails, and a user who has hit a failure cannot go back and turn logging on.
 */
internal object OnePlusLog {

    private val logger: Logger = LoggerFactory.getLogger(OnePlusLogMarkers.TAG)

    fun d(message: String) = logger.debug(message)

    fun i(message: String) = logger.info(message)

    fun w(message: String) = logger.warn(message)

    fun e(message: String) = logger.error(message)

    fun e(message: String, cause: Throwable) = logger.error(message, cause)
}
