package app.aaps.plugins.sync.tidepool.utils

import app.aaps.plugins.sync.tidepool.elements.BaseElement
import app.aaps.plugins.sync.tidepool.messages.BaseMessage
import com.google.gson.JsonElement
import com.google.gson.JsonParser

/** The JSON Tidepool receives for a record, a message or a list of records, ready for assertions. */
internal object TidepoolTestJson {

    fun of(value: Any): JsonElement = JsonParser.parseString(text(value))

    private fun text(value: Any): String = when (value) {
        is BaseElement -> value.toJson().toString()
        is BaseMessage -> value.toJson().toString()
        is List<*>     -> value.joinToString(",", "[", "]") { text(it!!) }
        else           -> error("no toJson() for ${value::class.simpleName}")
    }
}
