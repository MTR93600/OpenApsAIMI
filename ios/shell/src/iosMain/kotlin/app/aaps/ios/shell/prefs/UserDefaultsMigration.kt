package app.aaps.ios.shell.prefs

import androidx.datastore.core.DataMigration
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.sharedPreferences.StoreCipher
import app.aaps.shared.impl.sharedPreferences.StoreCodec
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toKString
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSUserDefaults

/**
 * Moves the settings that `IosSp` kept in `NSUserDefaults` (until 2026-10) into the encrypted store, once.
 *
 * DataStore runs it before the store is first read, writes the result, and only then calls [cleanUp].
 * [cleanUp] reads the written file back and removes the moved keys from `NSUserDefaults` only when all of
 * them are there. If the start is cut short in between, the next start does the same again: values
 * already in the store win, because they can only be newer.
 *
 * What moves is what `IosSp` could write: text, true/false, whole numbers and decimals. Anything else in
 * the app's defaults - a date, data, a list - was not written by AAPS and stays where it is. A whole
 * number moves as a Long, because `NSUserDefaults` keeps Int and Long the same way; the store reads a
 * Long back as an Int when asked for one.
 *
 * Only once. Other code still writes to the same `NSUserDefaults`: the Medtrum driver keeps the patch
 * there (`IosMedtrumStateStore`, keys starting with `medtrum.`). A move on every start took those keys
 * too, so after each restart the driver knew no patch, said the pump was stopped, and the loop
 * suspended itself. So:
 *
 * - keys of such a driver ([DRIVER_KEY_PREFIXES]) never move;
 * - after the first move that worked, [MOVE_DONE_KEY] is set and nothing moves again;
 * - driver keys that an older build already moved into the store are given back to `NSUserDefaults`
 *   (only where `NSUserDefaults` has no value of its own, which is always newer) and leave the store.
 */
class UserDefaultsMigration(
    private val defaults: NSUserDefaults,
    /** The app's own domain, the bundle id. Its persistent domain holds only what the app wrote. */
    private val domain: String,
    private val storePath: String,
    private val cipher: StoreCipher,
    private val log: AAPSLogger
) : DataMigration<Map<String, Any>> {

    private var movedKeys: Set<String> = emptySet()

    override suspend fun shouldMigrate(currentData: Map<String, Any>): Boolean =
        driverValues(currentData).isNotEmpty() || (!moveDone() && values().isNotEmpty())

    override suspend fun migrate(currentData: Map<String, Any>): Map<String, Any> {
        val takenBack = driverValues(currentData)
        if (takenBack.isNotEmpty()) giveBack(takenBack)
        val values = if (moveDone()) emptyMap() else values()
        movedKeys = values.keys
        return values + (currentData - takenBack.keys)
    }

    override suspend fun cleanUp() {
        if (movedKeys.isEmpty()) return
        // Never throws: DataStore would fail the whole start for it, while the next start simply tries again.
        try {
            val missing = movedKeys - StoreCodec.decode(readStoreFile(), cipher).keys
            if (missing.isNotEmpty()) {
                log.error(LTag.CORE, "${missing.size} settings are not in the new store yet; they stay in NSUserDefaults")
                return
            }
            movedKeys.forEach { defaults.removeObjectForKey(it) }
            defaults.setBool(true, forKey = MOVE_DONE_KEY)
            log.info(LTag.CORE, "Moved ${movedKeys.size} settings from NSUserDefaults into the encrypted store")
        } catch (e: Exception) {
            log.error(LTag.CORE, "Could not check the moved settings; they stay in NSUserDefaults: ${e.message}")
        }
    }

    /** The values of the app's domain that the store can hold and that are not a driver's. */
    internal fun values(): Map<String, Any> =
        defaults.persistentDomainForName(domain).orEmpty().entries.mapNotNull { (key, value) ->
            val name = key as? String ?: return@mapNotNull null
            if (name == MOVE_DONE_KEY || isDriverKey(name)) return@mapNotNull null
            storable(value)?.let { name to it }
        }.toMap()

    private fun moveDone(): Boolean = defaults.boolForKey(MOVE_DONE_KEY)

    /** Driver keys that an older build moved into the store by mistake. */
    private fun driverValues(currentData: Map<String, Any>): Map<String, Any> = currentData.filterKeys(::isDriverKey)

    /** Puts driver values back where the driver reads them. A value the driver wrote since is newer and stays. */
    private fun giveBack(values: Map<String, Any>) {
        var given = 0
        values.forEach { (key, value) ->
            if (defaults.objectForKey(key) != null) return@forEach
            when (value) {
                is String  -> defaults.setObject(value, forKey = key)
                is Boolean -> defaults.setBool(value, forKey = key)
                is Long    -> defaults.setInteger(value, forKey = key)
                is Int     -> defaults.setInteger(value.toLong(), forKey = key)
                is Double  -> defaults.setDouble(value, forKey = key)
                else       -> return@forEach
            }
            given++
        }
        defaults.synchronize()
        log.info(LTag.CORE, "Gave $given of ${values.size} pump driver values back to NSUserDefaults and removed them from the settings store")
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun readStoreFile(): ByteArray {
        val data = NSFileManager.defaultManager.contentsAtPath(storePath) ?: error("$storePath was not written")
        return data.bytes?.readBytes(data.length.toInt()) ?: ByteArray(0)
    }

    companion object {

        /** Set in `NSUserDefaults` after the one move that worked. */
        internal const val MOVE_DONE_KEY = "aaps.settings_moved_to_store"

        /**
         * Keys that other code keeps in the same `NSUserDefaults` and that are not settings. The Medtrum
         * driver's prefix is the default of `IosMedtrumStateStore`; a driver that stores its state here
         * must be added to this list.
         */
        internal val DRIVER_KEY_PREFIXES = listOf("medtrum.")

        internal fun isDriverKey(name: String): Boolean = DRIVER_KEY_PREFIXES.any { name.startsWith(it) }

        /**
         * A value of `NSUserDefaults` as the store holds it, or null when the store cannot hold it.
         *
         * A number read back from the defaults file is an `NSNumber` that only its type code describes:
         * `c` (or `B`) is a true/false, `f` and `d` are decimals, every other code is a whole number. The
         * Kotlin types come first for a value still in memory from this run.
         */
        @OptIn(ExperimentalForeignApi::class)
        internal fun storable(value: Any?): Any? = when (value) {
            is String, is Boolean, is Long, is Double -> value
            is Int                                    -> value.toLong()
            is NSNumber                               -> when (value.objCType?.toKString()) {
                "c", "B" -> value.boolValue
                "f", "d" -> value.doubleValue
                else     -> value.longLongValue
            }

            else                                      -> null
        }
    }
}
