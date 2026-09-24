package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** Reads a kotlinx [JsonObject] with the org.json names the tip tests used. */

internal fun JsonObject.has(key: String): Boolean = containsKey(key)

internal fun JsonObject.isNull(key: String): Boolean = this[key] is JsonNull

internal fun JsonObject.getJSONObject(key: String): JsonObject = this.getValue(key).jsonObject

internal fun JsonObject.getDouble(key: String): Double = this.getValue(key).jsonPrimitive.double

internal fun JsonObject.getBoolean(key: String): Boolean = this.getValue(key).jsonPrimitive.boolean

internal fun JsonObject.getString(key: String): String = this.getValue(key).jsonPrimitive.content

internal fun JsonObject.getLong(key: String): Long = this.getValue(key).jsonPrimitive.long
