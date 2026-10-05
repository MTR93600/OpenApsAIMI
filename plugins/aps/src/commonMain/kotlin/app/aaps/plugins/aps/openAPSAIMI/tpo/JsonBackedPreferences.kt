package app.aaps.plugins.aps.openAPSAIMI.tpo

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
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

/**
 * Preference values TPO reads and writes, stored beside the session file.
 *
 * Android keeps these in its own preference store and passes that store into the session manager.
 * iOS has no such store. This file is that store: `tpo/aimi_preferences.json`, under the same
 * directory as `tpo/tpo_session.json`. A missing file is every key at its default, which is what
 * a phone does before the user or a session has written the key. A write that fails stays in
 * memory for this process and answers `false` from storage, so a dosing tick is not taken down.
 */
internal class JsonBackedPreferences(
    private val storage: AimiStorage,
) : Preferences {

    private val doubles = mutableMapOf<String, Double>()
    private val bools = mutableMapOf<String, Boolean>()

    private val prettyJson = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    init {
        load()
    }

    override val simpleMode: Boolean = false
    override val apsMode: Boolean = true
    override val nsclientMode: Boolean = false
    override val pumpControlMode: Boolean = false

    override fun get(key: BooleanNonPreferenceKey): Boolean = bools[key.key] ?: key.defaultValue
    override fun getIfExists(key: BooleanNonPreferenceKey): Boolean? = bools[key.key]
    override fun put(key: BooleanNonPreferenceKey, value: Boolean) {
        bools[key.key] = value
        save()
    }
    override fun observe(key: BooleanNonPreferenceKey): StateFlow<Boolean> = MutableStateFlow(get(key))
    override fun get(key: BooleanPreferenceKey): Boolean = bools[key.key] ?: key.defaultValue
    override fun get(key: BooleanComposedNonPreferenceKey, vararg arguments: Any): Boolean = key.defaultValue
    override fun get(key: BooleanComposedNonPreferenceKey, vararg arguments: Any, defaultValue: Boolean): Boolean =
        defaultValue
    override fun getIfExists(key: BooleanComposedNonPreferenceKey, vararg arguments: Any): Boolean? = null
    override fun put(key: BooleanComposedNonPreferenceKey, vararg arguments: Any, value: Boolean) = Unit
    override fun observe(key: BooleanComposedNonPreferenceKey, vararg arguments: Any): StateFlow<Boolean> =
        MutableStateFlow(key.defaultValue)
    override fun remove(key: ComposedKey, vararg arguments: Any) = Unit
    override fun get(key: StringNonPreferenceKey): String = key.defaultValue
    override fun getIfExists(key: StringNonPreferenceKey): String? = null
    override fun put(key: StringNonPreferenceKey, value: String) = Unit
    override fun observe(key: StringNonPreferenceKey): StateFlow<String> = MutableStateFlow(key.defaultValue)
    override fun get(key: StringPreferenceKey): String = key.defaultValue
    override fun get(key: StringComposedNonPreferenceKey, vararg arguments: Any): String = key.defaultValue
    override fun getIfExists(key: StringComposedNonPreferenceKey, vararg arguments: Any): String? = null
    override fun put(key: StringComposedNonPreferenceKey, vararg arguments: Any, value: String) = Unit
    override fun observe(key: StringComposedNonPreferenceKey, vararg arguments: Any): StateFlow<String> =
        MutableStateFlow(key.defaultValue)
    override fun get(key: DoubleNonPreferenceKey): Double = doubles[key.key] ?: key.defaultValue
    override fun get(key: DoublePreferenceKey): Double = doubles[key.key] ?: key.defaultValue
    override fun getIfExists(key: DoublePreferenceKey): Double? = doubles[key.key]
    override fun put(key: DoubleNonPreferenceKey, value: Double) {
        doubles[key.key] = value
        save()
    }
    override fun observe(key: DoubleNonPreferenceKey): StateFlow<Double> = MutableStateFlow(get(key))
    override fun get(key: DoubleComposedNonPreferenceKey, vararg arguments: Any): Double = key.defaultValue
    override fun getIfExists(key: DoubleComposedNonPreferenceKey, vararg arguments: Any): Double? = null
    override fun put(key: DoubleComposedNonPreferenceKey, vararg arguments: Any, value: Double) = Unit
    override fun observe(key: DoubleComposedNonPreferenceKey, vararg arguments: Any): StateFlow<Double> =
        MutableStateFlow(key.defaultValue)
    override fun get(key: UnitDoublePreferenceKey): Double = key.defaultValue
    override fun getIfExists(key: UnitDoublePreferenceKey): Double? = null
    override fun put(key: UnitDoublePreferenceKey, value: Double) = Unit
    override fun observe(key: UnitDoublePreferenceKey): StateFlow<Double> = MutableStateFlow(key.defaultValue)
    override fun get(key: IntNonPreferenceKey): Int = key.defaultValue
    override fun getIfExists(key: IntNonPreferenceKey): Int? = null
    override fun put(key: IntComposedNonPreferenceKey, vararg arguments: Any, value: Int) = Unit
    override fun put(key: IntNonPreferenceKey, value: Int) = Unit
    override fun observe(key: IntNonPreferenceKey): StateFlow<Int> = MutableStateFlow(key.defaultValue)
    override fun inc(key: IntNonPreferenceKey) = Unit
    override fun get(key: IntComposedNonPreferenceKey, vararg arguments: Any): Int = key.defaultValue
    override fun observe(key: IntComposedNonPreferenceKey, vararg arguments: Any): StateFlow<Int> =
        MutableStateFlow(key.defaultValue)
    override fun get(key: IntPreferenceKey): Int = key.defaultValue
    override fun get(key: LongNonPreferenceKey): Long = key.defaultValue
    override fun getIfExists(key: LongNonPreferenceKey): Long? = null
    override fun put(key: LongNonPreferenceKey, value: Long) = Unit
    override fun observe(key: LongNonPreferenceKey): StateFlow<Long> = MutableStateFlow(key.defaultValue)
    override fun get(key: LongPreferenceKey): Long = key.defaultValue
    override fun inc(key: LongNonPreferenceKey) = Unit
    override fun get(key: LongComposedNonPreferenceKey, vararg arguments: Any): Long = key.defaultValue
    override fun getIfExists(key: LongComposedNonPreferenceKey, vararg arguments: Any): Long? = null
    override fun put(key: LongComposedNonPreferenceKey, vararg arguments: Any, value: Long) = Unit
    override fun observe(key: LongComposedNonPreferenceKey, vararg arguments: Any): StateFlow<Long> =
        MutableStateFlow(key.defaultValue)
    override fun remove(key: NonPreferenceKey) {
        doubles.remove(key.key)
        bools.remove(key.key)
        save()
    }
    override fun isUnitDependent(key: String): Boolean = false
    override fun get(key: String): NonPreferenceKey? = null
    override fun getIfExists(key: String): NonPreferenceKey? = null
    override fun registerPreferences(keys: List<NonPreferenceKey>) = Unit
    override fun allMatchingStrings(key: ComposedKey): List<String> = emptyList()
    override fun allMatchingInts(key: ComposedKey): List<Int> = emptyList()
    override fun isExportableKey(key: String): Boolean = false
    override fun getAllPreferenceKeys(): List<PreferenceKey> = emptyList()

    private fun preferencesFile() = storage.file("tpo", FILE_NAME)

    private fun load() {
        val path = preferencesFile()
        if (!storage.exists(path)) return
        val json = runCatching {
            Json.parseToJsonElement(storage.readText(path).orEmpty()).jsonObject
        }.getOrNull() ?: return
        json["doubles"]?.jsonObject?.forEach { (key, value) ->
            value.jsonPrimitive.doubleOrNull?.let { doubles[key] = it }
        }
        json["bools"]?.jsonObject?.forEach { (key, value) ->
            val primitive = value as? JsonPrimitive ?: return@forEach
            primitive.booleanOrNull?.let { bools[key] = it }
        }
    }

    private fun save() {
        val json = buildJsonObject {
            put("doubles", buildJsonObject { doubles.forEach { (key, value) -> put(key, value) } })
            put("bools", buildJsonObject { bools.forEach { (key, value) -> put(key, value) } })
        }
        storage.replaceText(
            preferencesFile(),
            prettyJson.encodeToString(serializer<JsonElement>(), json),
        )
    }

    private companion object {
        const val FILE_NAME = "aimi_preferences.json"
    }
}
