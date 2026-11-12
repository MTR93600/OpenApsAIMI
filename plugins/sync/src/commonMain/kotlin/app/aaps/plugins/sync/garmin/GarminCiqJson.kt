package app.aaps.plugins.sync.garmin

/**
 * The glucose push as JSON text, so Swift can turn it into the dictionary the Connect IQ SDK sends.
 *
 * Key order is the map's order. Numbers are written the way Kotlin prints them (`1.0`, `1.25`),
 * which is also how the Android message's doubles are read back. This is the logical payload. The
 * bytes on the watch are the SDK's encoding of that dictionary; [GarminCiqBytes] is the Android
 * encoding of the same map, kept so the two can be compared.
 */
internal object GarminCiqJson {

    fun of(message: Map<String, Any?>): String = buildString {
        append('{')
        message.entries.forEachIndexed { index, (key, value) ->
            if (index > 0) append(',')
            string(key)
            append(':')
            value(value)
        }
        append('}')
    }

    private fun StringBuilder.value(value: Any?) {
        when (value) {
            null -> append("null")
            is String -> string(value)
            is Boolean -> append(if (value) "true" else "false")
            is Int, is Long -> append(value.toString())
            is Float, is Double -> {
                val number = (value as Number).toDouble()
                if (!number.isFinite()) append("null") else append(number.toString())
            }
            else -> throw IllegalArgumentException("Unsupported Connect IQ value ${value::class}")
        }
    }

    private fun StringBuilder.string(text: String) {
        append('"')
        for (char in text) {
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append(hex4(char.code)) else append(char)
            }
        }
        append('"')
    }

    private fun hex4(code: Int): String {
        val hex = "0123456789abcdef"
        return buildString(6) {
            append("\\u")
            for (shift in intArrayOf(12, 8, 4, 0)) append(hex[(code shr shift) and 0xF])
        }
    }
}
