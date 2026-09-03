package app.aaps.plugins.aps.openAPSAIMI.utils

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A small builder with `org.json`'s shape, producing `kotlinx.serialization` values.
 *
 * It exists so that `DetermineBasalAIMI2.toMedicalJson()` - 305 lines and 200 `put` calls that build
 * one string - can leave `org.json` without any of those 200 lines changing. Converting them by hand
 * into a `buildJsonObject { }` DSL would be a restructuring of code that no compiler checks while it
 * sits parked, and the diff would be unreviewable.
 *
 * **This is a transitional type.** Once the fields it feeds are `kotlinx` natively, the call sites can
 * move to the real DSL and this goes away.
 *
 * ### Behaviour is `org.json`'s, deliberately
 *
 * `org.json` rejects a non-finite `Double`, and `toMedicalJson` wraps everything in a `catch` that
 * returns `{ "error": "JSON Generation Failed" }`. So today one NaN anywhere destroys the whole
 * medical export. [toElement] throws on a non-finite value for exactly that reason: the conversion
 * must not change behaviour, and this reproduces it.
 *
 * Whether an export should really be all-or-nothing is a fair question, and a separate one. Changing
 * it here would hide a real defect behind a refactor.
 */
internal object AimiJson {

    /** Stands in for `org.json.JSONObject.NULL`: an explicit JSON `null`, not an absent key. */
    val NULL: Any = NullSentinel

    private object NullSentinel
}

/** Mirrors `org.json.JSONObject`'s building surface. */
internal class JsonObj {

    private val members = LinkedHashMap<String, JsonElement>()

    fun put(name: String, value: Any?): JsonObj {
        members[name] = toElement(value)
        return this
    }

    /**
     * Writes [value] only when it is finite, and an explicit JSON `null` otherwise.
     *
     * Mirrors the `JsonObjectBuilder` extension of the same name in `patient/`, so a call site reads
     * the same whichever builder it is on. Note this does NOT throw on a non-finite value - a caller
     * choosing this function is saying it would rather record an absent number than fail the export.
     */
    fun putFiniteOrNull(name: String, value: Double?): JsonObj {
        members[name] = if (value != null && value.isFinite()) JsonPrimitive(value) else JsonNull
        return this
    }

    fun build(): JsonObject = JsonObject(members)

    override fun toString(): String = build().toString()
}

/** Mirrors `org.json.JSONArray`'s building surface, including the collection constructor. */
internal class JsonArr {

    private val items = mutableListOf<JsonElement>()

    constructor()

    constructor(from: Collection<*>) {
        from.forEach { items += toElement(it) }
    }

    fun put(value: Any?): JsonArr {
        items += toElement(value)
        return this
    }

    fun build(): JsonArray = JsonArray(items)

    override fun toString(): String = build().toString()
}

/**
 * The one place the value rules live, rather than at 200 call sites.
 *
 * A non-finite `Double` or `Float` throws, matching `org.json`. Nothing else here can fail.
 */
private fun toElement(value: Any?): JsonElement = when (value) {
    null            -> JsonNull
    AimiJson.NULL   -> JsonNull
    is JsonElement  -> value
    is JsonObj      -> value.build()
    is JsonArr      -> value.build()
    is String       -> JsonPrimitive(value)
    is Boolean      -> JsonPrimitive(value)
    is Double       -> {
        require(value.isFinite()) { "Non-finite Double is not valid JSON" }
        JsonPrimitive(value)
    }
    is Float        -> {
        require(value.isFinite()) { "Non-finite Float is not valid JSON" }
        JsonPrimitive(value)
    }
    is Number       -> JsonPrimitive(value)
    is Enum<*>      -> JsonPrimitive(value.name)
    else            -> JsonPrimitive(value.toString())
}
