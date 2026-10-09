package app.aaps.implementation.sharedPreferences

import app.aaps.core.interfaces.sharedPreferences.KeyValueStore
import platform.Foundation.NSUserDefaults

/**
 * The iOS side of AAPS preference storage, backed by NSUserDefaults.
 *
 * `SP` (Android) is the only genuinely platform specific part of preferences. The layer above it,
 * `PreferencesImpl`, is plain Kotlin in commonMain - key lookup, defaults and range clamping - so
 * iOS does not need another `Preferences`, it needs a store for that one to sit on, which is this.
 * Same pattern as `DesktopSp` on desktop.
 *
 * Every getter returns the caller's default when the key is absent, and also when the stored value
 * cannot be read as the type asked for. NSUserDefaults returns 0/0.0/false for missing keys, so a
 * missing key must not be confused with a stored zero: [contains] is checked first, and the
 * default is returned when the key is absent.
 *
 * Doubles and longs are stored via NSUserDefaults' native double/integer slots. Ints are stored
 * as integers and read back with a range check.
 */
class IosKeyValueStore(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults
) : KeyValueStore {

    private inner class IosEditor : KeyValueStore.Editor {

        override fun clear() {
            for (key in defaults.dictionaryRepresentation().keys) {
                defaults.removeObjectForKey(key as String)
            }
        }

        override fun remove(key: String) {
            defaults.removeObjectForKey(key)
        }

        override fun putBoolean(key: String, value: Boolean) {
            defaults.setBool(value, key)
        }

        override fun putDouble(key: String, value: Double) {
            defaults.setDouble(value, key)
        }

        override fun putLong(key: String, value: Long) {
            defaults.setInteger(value, key)
        }

        override fun putInt(key: String, value: Int) {
            defaults.setInteger(value.toLong(), key)
        }

        override fun putString(key: String, value: String) {
            defaults.setObject(value, key)
        }
    }

    override fun edit(commit: Boolean, block: KeyValueStore.Editor.() -> Unit) {
        IosEditor().block()
        if (commit) defaults.synchronize()
    }

    override fun getAll(): Map<String, *> =
        defaults.dictionaryRepresentation().mapNotNull { (k, v) ->
            (k as? String)?.let { it to v }
        }.toMap()

    override fun clear() {
        for (key in defaults.dictionaryRepresentation().keys) {
            defaults.removeObjectForKey(key as String)
        }
    }

    override fun contains(key: String): Boolean =
        defaults.dictionaryRepresentation().containsKey(key)

    override fun remove(key: String) {
        defaults.removeObjectForKey(key)
    }

    override fun getString(key: String, defaultValue: String): String =
        defaults.stringForKey(key) ?: defaultValue

    override fun getStringOrNull(key: String, defaultValue: String?): String? =
        defaults.stringForKey(key) ?: defaultValue

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        if (contains(key)) defaults.boolForKey(key) else defaultValue

    override fun getDouble(key: String, defaultValue: Double): Double =
        if (contains(key)) defaults.doubleForKey(key) else defaultValue

    override fun getInt(key: String, defaultValue: Int): Int =
        if (contains(key)) defaults.integerForKey(key).toInt() else defaultValue

    override fun getLong(key: String, defaultValue: Long): Long =
        if (contains(key)) defaults.integerForKey(key) else defaultValue

    override fun putString(key: String, value: String) {
        defaults.setObject(value, key)
    }

    override fun putBoolean(key: String, value: Boolean) {
        defaults.setBool(value, key)
    }

    override fun putDouble(key: String, value: Double) {
        defaults.setDouble(value, key)
    }

    override fun putInt(key: String, value: Int) {
        defaults.setInteger(value.toLong(), key)
    }

    override fun putLong(key: String, value: Long) {
        defaults.setInteger(value, key)
    }

    override fun incInt(key: String) = putInt(key, getInt(key, 0) + 1)

    override fun incLong(key: String) = putLong(key, getLong(key, 0L) + 1L)
}
