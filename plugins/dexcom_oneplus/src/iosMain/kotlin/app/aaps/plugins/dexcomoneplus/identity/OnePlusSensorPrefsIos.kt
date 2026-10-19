package app.aaps.plugins.dexcomoneplus.identity

import platform.Foundation.NSUserDefaults

/**
 * iOS [OnePlusSensorPrefs], over `NSUserDefaults`.
 *
 * The file name of the Android side becomes a suite name here, so each sensor slot keeps its own
 * store exactly as it does on a phone. The data is app private on both platforms: a suite lives in
 * the app container, the same way a private `SharedPreferences` file lives in the app's data
 * directory. It is not encrypted on either one, so the pairing code and the key are as safe as the
 * app container is, and no safer.
 *
 * `NSUserDefaults` differs from `SharedPreferences` in two ways this class has to hide, because
 * [OnePlusSensorPrefs] is one contract that both platforms answer the same way:
 *
 * - **A missing key reads as zero or false, not as missing.** `integerForKey` and `boolForKey`
 *   answer `0` / `false` for "absent" as well as for a stored zero, so every getter asks
 *   `objectForKey` first and only then reads the value. Without that, a slot that had never
 *   recorded its soak start would read as "started at the epoch", which is a settled sensor.
 * - **There is no batch.** Android's editor applies every change of one [edit] call together. The
 *   changes are collected here and written in one pass at the end of the block, which is as close
 *   as this store gets; a write is to memory and is flushed by the system, so a half applied batch
 *   needs the process to die inside the loop.
 */
internal class OnePlusSensorPrefsIos(suiteName: String) : OnePlusSensorPrefs {

    /**
     * The slot's own suite.
     *
     * The name comes from `OnePlusSensorStore.prefsFileName`, so it is never the bundle id and
     * never the global domain - the two names `initWithSuiteName:` refuses.
     */
    private val defaults: NSUserDefaults = NSUserDefaults(suiteName = suiteName)

    override fun getString(key: String): String? = defaults.stringForKey(key)

    override fun getLong(key: String, defaultValue: Long): Long =
        if (defaults.objectForKey(key) == null) defaultValue else defaults.integerForKey(key)

    override fun getInt(key: String, defaultValue: Int): Int =
        if (defaults.objectForKey(key) == null) defaultValue else defaults.integerForKey(key).toInt()

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        if (defaults.objectForKey(key) == null) defaultValue else defaults.boolForKey(key)

    override fun contains(key: String): Boolean = defaults.objectForKey(key) != null

    override fun edit(block: OnePlusSensorPrefs.Editor.() -> Unit) {
        val editor = IosEditor()
        editor.block()
        editor.applyTo(defaults)
    }

    /** Collects one [edit] block, then writes it in a single pass. */
    private class IosEditor : OnePlusSensorPrefs.Editor {

        /** Changes in the order they were made; a null value means "remove this key". */
        private val changes = mutableListOf<Pair<String, Any?>>()

        private var clearFirst = false

        override fun putString(key: String, value: String?) {
            changes += key to value
        }

        override fun putLong(key: String, value: Long) {
            changes += key to value
        }

        override fun putInt(key: String, value: Int) {
            changes += key to value
        }

        override fun putBoolean(key: String, value: Boolean) {
            changes += key to value
        }

        override fun remove(key: String) {
            changes += key to null
        }

        override fun clear() {
            clearFirst = true
            changes.clear()
        }

        fun applyTo(defaults: NSUserDefaults) {
            if (clearFirst) {
                // Only this suite's own keys, the same reach `SharedPreferences.clear()` has.
                defaults.dictionaryRepresentation().keys.forEach { key ->
                    (key as? String)?.let { defaults.removeObjectForKey(it) }
                }
            }
            changes.forEach { (key, value) ->
                when (value) {
                    null       -> defaults.removeObjectForKey(key)
                    is String  -> defaults.setObject(value, forKey = key)
                    is Long    -> defaults.setInteger(value, forKey = key)
                    is Int     -> defaults.setInteger(value.toLong(), forKey = key)
                    is Boolean -> defaults.setBool(value, forKey = key)
                    else       -> defaults.setObject(value.toString(), forKey = key)
                }
            }
        }
    }
}

/**
 * Builds a store on this phone's own suite for [namespace].
 *
 * Named like the class on purpose, the same as the Android one, so a caller reads the same on both
 * platforms.
 */
fun OnePlusSensorStore(namespace: String? = null): OnePlusSensorStore =
    OnePlusSensorStore(OnePlusSensorPrefsIos(OnePlusSensorStore.prefsFileName(namespace)))
