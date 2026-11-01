package app.aaps.pump.medtrum

import app.aaps.core.keys.interfaces.BooleanComposedNonPreferenceKey
import app.aaps.core.keys.interfaces.BooleanNonPreferenceKey
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.ComposedKey
import app.aaps.core.keys.interfaces.DoubleComposedNonPreferenceKey
import app.aaps.core.keys.interfaces.DoubleNonPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey
import app.aaps.core.keys.interfaces.IntComposedNonPreferenceKey
import app.aaps.core.keys.interfaces.IntNonPreferenceKey
import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.core.keys.interfaces.LongComposedNonPreferenceKey
import app.aaps.core.keys.interfaces.LongNonPreferenceKey
import app.aaps.core.keys.interfaces.LongPreferenceKey
import app.aaps.core.keys.interfaces.NonPreferenceKey
import app.aaps.core.keys.interfaces.PreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.StringComposedNonPreferenceKey
import app.aaps.core.keys.interfaces.StringNonPreferenceKey
import app.aaps.core.keys.interfaces.StringPreferenceKey
import app.aaps.core.keys.interfaces.UnitDoublePreferenceKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * [Preferences] for the Medtrum settings tests. Boolean, int and string values are kept per key, and
 * `observe` gives one live flow per key, so a `put` reaches a collector like on a phone. Every other
 * kind of key answers its default and ignores writes.
 *
 * For shared tests: `commonTest` has no Mockito, so this is written out by hand.
 */
internal class FakeMedtrumPreferences : Preferences {

    private val booleans = mutableMapOf<String, MutableStateFlow<Boolean>>()
    private val ints = mutableMapOf<String, MutableStateFlow<Int>>()
    private val strings = mutableMapOf<String, MutableStateFlow<String>>()

    private fun flow(key: BooleanNonPreferenceKey) = booleans.getOrPut(key.key) { MutableStateFlow(key.defaultValue) }
    private fun flow(key: IntNonPreferenceKey) = ints.getOrPut(key.key) { MutableStateFlow(key.defaultValue) }
    private fun flow(key: StringNonPreferenceKey) = strings.getOrPut(key.key) { MutableStateFlow(key.defaultValue) }

    override val simpleMode: Boolean = false
    override val apsMode: Boolean = true
    override val nsclientMode: Boolean = false
    override val pumpControlMode: Boolean = false

    override fun get(key: BooleanNonPreferenceKey): Boolean = flow(key).value
    override fun getIfExists(key: BooleanNonPreferenceKey): Boolean = flow(key).value
    override fun put(key: BooleanNonPreferenceKey, value: Boolean) {
        flow(key).value = value
    }
    override fun observe(key: BooleanNonPreferenceKey): StateFlow<Boolean> = flow(key)
    override fun get(key: BooleanPreferenceKey): Boolean = flow(key).value
    override fun get(key: BooleanComposedNonPreferenceKey, vararg arguments: Any): Boolean = key.defaultValue
    override fun get(key: BooleanComposedNonPreferenceKey, vararg arguments: Any, defaultValue: Boolean): Boolean = defaultValue
    override fun getIfExists(key: BooleanComposedNonPreferenceKey, vararg arguments: Any): Boolean = key.defaultValue
    override fun put(key: BooleanComposedNonPreferenceKey, vararg arguments: Any, value: Boolean) {}
    override fun observe(key: BooleanComposedNonPreferenceKey, vararg arguments: Any): StateFlow<Boolean> =
        MutableStateFlow(key.defaultValue)
    override fun remove(key: ComposedKey, vararg arguments: Any) {}
    override fun get(key: StringNonPreferenceKey): String = flow(key).value
    override fun getIfExists(key: StringNonPreferenceKey): String = flow(key).value
    override fun put(key: StringNonPreferenceKey, value: String) {
        flow(key).value = value
    }
    override fun observe(key: StringNonPreferenceKey): StateFlow<String> = flow(key)
    override fun get(key: StringPreferenceKey): String = flow(key).value
    override fun get(key: StringComposedNonPreferenceKey, vararg arguments: Any): String = key.defaultValue
    override fun getIfExists(key: StringComposedNonPreferenceKey, vararg arguments: Any): String = key.defaultValue
    override fun put(key: StringComposedNonPreferenceKey, vararg arguments: Any, value: String) {}
    override fun observe(key: StringComposedNonPreferenceKey, vararg arguments: Any): StateFlow<String> =
        MutableStateFlow(key.defaultValue)
    override fun get(key: DoubleNonPreferenceKey): Double = key.defaultValue
    override fun get(key: DoublePreferenceKey): Double = key.defaultValue
    override fun getIfExists(key: DoublePreferenceKey): Double = key.defaultValue
    override fun put(key: DoubleNonPreferenceKey, value: Double) {}
    override fun observe(key: DoubleNonPreferenceKey): StateFlow<Double> = MutableStateFlow(key.defaultValue)
    override fun get(key: DoubleComposedNonPreferenceKey, vararg arguments: Any): Double = key.defaultValue
    override fun getIfExists(key: DoubleComposedNonPreferenceKey, vararg arguments: Any): Double = key.defaultValue
    override fun put(key: DoubleComposedNonPreferenceKey, vararg arguments: Any, value: Double) {}
    override fun observe(key: DoubleComposedNonPreferenceKey, vararg arguments: Any): StateFlow<Double> =
        MutableStateFlow(key.defaultValue)
    override fun get(key: UnitDoublePreferenceKey): Double = key.defaultValue
    override fun getIfExists(key: UnitDoublePreferenceKey): Double = key.defaultValue
    override fun put(key: UnitDoublePreferenceKey, value: Double) {}
    override fun observe(key: UnitDoublePreferenceKey): StateFlow<Double> = MutableStateFlow(key.defaultValue)
    override fun get(key: IntNonPreferenceKey): Int = flow(key).value
    override fun getIfExists(key: IntNonPreferenceKey): Int = flow(key).value
    override fun put(key: IntComposedNonPreferenceKey, vararg arguments: Any, value: Int) {}
    override fun put(key: IntNonPreferenceKey, value: Int) {
        flow(key).value = value
    }
    override fun observe(key: IntNonPreferenceKey): StateFlow<Int> = flow(key)
    override fun inc(key: IntNonPreferenceKey) {
        flow(key).value += 1
    }
    override fun get(key: IntComposedNonPreferenceKey, vararg arguments: Any): Int = key.defaultValue
    override fun observe(key: IntComposedNonPreferenceKey, vararg arguments: Any): StateFlow<Int> =
        MutableStateFlow(key.defaultValue)
    override fun get(key: IntPreferenceKey): Int = flow(key).value
    override fun get(key: LongNonPreferenceKey): Long = key.defaultValue
    override fun getIfExists(key: LongNonPreferenceKey): Long = key.defaultValue
    override fun put(key: LongNonPreferenceKey, value: Long) {}
    override fun observe(key: LongNonPreferenceKey): StateFlow<Long> = MutableStateFlow(key.defaultValue)
    override fun get(key: LongPreferenceKey): Long = key.defaultValue
    override fun inc(key: LongNonPreferenceKey) {}
    override fun get(key: LongComposedNonPreferenceKey, vararg arguments: Any): Long = key.defaultValue
    override fun getIfExists(key: LongComposedNonPreferenceKey, vararg arguments: Any): Long = key.defaultValue
    override fun put(key: LongComposedNonPreferenceKey, vararg arguments: Any, value: Long) {}
    override fun observe(key: LongComposedNonPreferenceKey, vararg arguments: Any): StateFlow<Long> =
        MutableStateFlow(key.defaultValue)
    override fun remove(key: NonPreferenceKey) {}
    override fun isUnitDependent(key: String): Boolean = false
    override fun get(key: String): NonPreferenceKey? = null
    override fun getIfExists(key: String): NonPreferenceKey? = null
    override fun registerPreferences(keys: List<NonPreferenceKey>) {}
    override fun allMatchingStrings(key: ComposedKey): List<String> = emptyList()
    override fun allMatchingInts(key: ComposedKey): List<Int> = emptyList()
    override fun isExportableKey(key: String): Boolean = false
    override fun getAllPreferenceKeys(): List<PreferenceKey> = emptyList()
    override fun getAllKeys(): List<NonPreferenceKey> = emptyList()
    override fun reloadFromStore() {}
}
