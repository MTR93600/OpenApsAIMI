package app.aaps.shared.impl.sharedPreferences

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.utils.SafeParse
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SPImpl @Inject constructor(
    private val sharedPreferences: SharedPreferences,
    private val context: Context
) : SP {

    /**
     * Doubles are saved as text. Reading one used to throw and catch a ClassCastException first (the float
     * read fails) and then parse the text again, on every read. The loop reads hundreds of doubles per
     * cycle, so this was a large part of the CPU use. Here the parsed value is kept in memory and dropped
     * when the stored value changes.
     */
    private val storedDoubles = ConcurrentHashMap<String, Double>()

    // A strong reference is needed: SharedPreferences keeps its listeners in a weak set.
    private val changeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
        if (changedKey == null) storedDoubles.clear() else storedDoubles.remove(changedKey)
    }

    init {
        sharedPreferences.registerOnSharedPreferenceChangeListener(changeListener)
    }

    private fun readDouble(key: String, defaultValue: Double): Double {
        storedDoubles[key]?.let { return it }
        return try {
            sharedPreferences.getFloat(key, defaultValue.toFloat()).toDouble()
        } catch (_: Exception) {
            val text = sharedPreferences.getString(key, null)
            val parsed = SafeParse.stringToDouble(text, Double.NaN)
            if (parsed.isNaN()) {
                SafeParse.stringToDouble(text ?: defaultValue.toString(), defaultValue)
            } else {
                storedDoubles[key] = parsed
                parsed
            }
        }
    }

    @SuppressLint("ApplySharedPref")
    override fun edit(commit: Boolean, block: SP.Editor.() -> Unit) {
        val spEdit = sharedPreferences.edit()

        val edit = object : SP.Editor {
            override fun clear() {
                spEdit.clear()
            }

            override fun remove(@StringRes resourceID: Int) {
                spEdit.remove(context.getString(resourceID))
            }

            override fun remove(key: String) {
                spEdit.remove(key)
            }

            override fun putBoolean(key: String, value: Boolean) {
                spEdit.putBoolean(key, value)
            }

            override fun putBoolean(@StringRes resourceID: Int, value: Boolean) {
                spEdit.putBoolean(context.getString(resourceID), value)
            }

            override fun putDouble(key: String, value: Double) {
                spEdit.putString(key, value.toString())
            }

            override fun putDouble(@StringRes resourceID: Int, value: Double) {
                spEdit.putString(context.getString(resourceID), value.toString())
            }

            override fun putLong(key: String, value: Long) {
                spEdit.putLong(key, value)
            }

            override fun putLong(@StringRes resourceID: Int, value: Long) {
                spEdit.putLong(context.getString(resourceID), value)
            }

            override fun putInt(key: String, value: Int) {
                spEdit.putInt(key, value)
            }

            override fun putInt(@StringRes resourceID: Int, value: Int) {
                spEdit.putInt(context.getString(resourceID), value)
            }

            override fun putString(key: String, value: String) {
                spEdit.putString(key, value)
            }

            override fun putString(@StringRes resourceID: Int, value: String) {
                spEdit.putString(context.getString(resourceID), value)
            }
        }

        block(edit)

        if (commit)
            spEdit.commit()
        else
            spEdit.apply()
        // The change listener runs later on the main thread; drop the cache here too, so nobody reads an old value.
        storedDoubles.clear()
    }

    override fun getAll(): Map<String, *> = sharedPreferences.all

    override fun clear() {
        sharedPreferences.edit().clear().apply()
        storedDoubles.clear()
    }

    override fun contains(key: String): Boolean = sharedPreferences.contains(key)

    override fun contains(resourceId: Int): Boolean = sharedPreferences.contains(context.getString(resourceId))

    override fun remove(resourceID: Int) {
        sharedPreferences.edit().remove(context.getString(resourceID)).apply()
        storedDoubles.clear()
    }

    override fun remove(key: String) {
        sharedPreferences.edit().remove(key).apply()
        storedDoubles.remove(key)
    }

    override fun getString(resourceID: Int, defaultValue: String): String =
        sharedPreferences.getString(context.getString(resourceID), defaultValue) ?: defaultValue

    override fun getString(key: String, defaultValue: String): String =
        sharedPreferences.getString(key, defaultValue) ?: defaultValue

    override fun getStringOrNull(resourceID: Int, defaultValue: String?): String? =
        sharedPreferences.getString(context.getString(resourceID), defaultValue) ?: defaultValue

    override fun getStringOrNull(key: String, defaultValue: String?): String? =
        sharedPreferences.getString(key, defaultValue)

    override fun getBoolean(resourceID: Int, defaultValue: Boolean): Boolean =
        sharedPreferences.getBoolean(context.getString(resourceID), defaultValue)

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        try {
            sharedPreferences.getBoolean(key, defaultValue)
        } catch (_: Exception) {
            defaultValue
        }

    override fun getDouble(resourceID: Int, defaultValue: Double): Double =
        readDouble(context.getString(resourceID), defaultValue)

    override fun getDouble(key: String, defaultValue: Double): Double =
        readDouble(key, defaultValue)

    override fun getInt(resourceID: Int, defaultValue: Int): Int =
        try {
            sharedPreferences.getInt(context.getString(resourceID), defaultValue)
        } catch (_: Exception) {
            SafeParse.stringToInt(sharedPreferences.getString(context.getString(resourceID), defaultValue.toString()), defaultValue)
        }

    override fun getInt(key: String, defaultValue: Int): Int =
        try {
            sharedPreferences.getInt(key, defaultValue)
        } catch (_: Exception) {
            SafeParse.stringToInt(sharedPreferences.getString(key, defaultValue.toString()), defaultValue)
        }

    override fun getLong(resourceID: Int, defaultValue: Long): Long =
        try {
            sharedPreferences.getLong(context.getString(resourceID), defaultValue)
        } catch (_: Exception) {
            SafeParse.stringToLong(sharedPreferences.getString(context.getString(resourceID), defaultValue.toString()), defaultValue)
        }

    override fun getLong(key: String, defaultValue: Long): Long =
        try {
            sharedPreferences.getLong(key, defaultValue)
        } catch (_: Exception) {
            SafeParse.stringToLong(sharedPreferences.getString(key, defaultValue.toString()), defaultValue)
        }

    /**
     * One direct write. The change listener runs later on the main thread, so the parsed copy of [key] is
     * dropped here at once: a loop run reading the key right after the write must get the new value, also
     * when the type changes (a double saved as text, then written again as a float by [putDouble]).
     */
    private fun write(key: String, block: SharedPreferences.Editor.(String) -> Unit) {
        sharedPreferences.edit().apply { block(key) }.apply()
        storedDoubles.remove(key)
    }

    override fun incLong(key: String) {
        val value = getLong(key, 0) + 1L
        write(key) { putLong(it, value) }
    }

    override fun putBoolean(key: String, value: Boolean) = write(key) { putBoolean(it, value) }

    override fun putBoolean(resourceID: Int, value: Boolean) = write(context.getString(resourceID)) { putBoolean(it, value) }

    override fun putDouble(key: String, value: Double) = write(key) { putFloat(it, value.toFloat()) }

    override fun putDouble(resourceID: Int, value: Double) = write(context.getString(resourceID)) { putFloat(it, value.toFloat()) }

    override fun putLong(key: String, value: Long) = write(key) { putLong(it, value) }

    override fun putLong(resourceID: Int, value: Long) = write(context.getString(resourceID)) { putLong(it, value) }

    override fun putInt(key: String, value: Int) = write(key) { putInt(it, value) }

    override fun putInt(resourceID: Int, value: Int) = write(context.getString(resourceID)) { putInt(it, value) }

    override fun incInt(key: String) {
        val value = getInt(key, 0) + 1
        write(key) { putInt(it, value) }
    }

    override fun putString(resourceID: Int, value: String) = write(context.getString(resourceID)) { putString(it, value) }

    override fun putString(key: String, value: String) = write(key) { putString(it, value) }

}