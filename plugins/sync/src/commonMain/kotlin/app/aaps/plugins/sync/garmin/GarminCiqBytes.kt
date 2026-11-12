package app.aaps.plugins.sync.garmin

/**
 * The Connect IQ mobile wire format, the same bytes Android's `GarminSerializer` writes into an
 * `IQMessage`.
 *
 * The watch apps are not changed. On Android the phone pushes a map and the serializer below (copied
 * here so the iPhone can build the same bytes) is what Garmin Connect delivers. The iPhone SDK takes
 * the same map and serializes it itself; these bytes are the contract the two encodings are checked
 * against, for one fixed message.
 *
 * The markers, type tags and string table are the ones `GarminSerializer` documents. Map order is
 * insertion order, which is the order [GarminRequests.getGlucoseMessage] inserts its keys.
 */
internal object GarminCiqBytes {

    private const val NULL = 0
    private const val INT = 1
    private const val FLOAT = 2
    private const val STRING = 3
    private const val ARRAY = 5
    private const val BOOLEAN = 9
    private const val MAP = 11
    private const val LONG = 14
    private const val DOUBLE = 15
    private const val CHAR = 19

    private const val STRINGS_MARKER = -1412584499
    private const val OBJECTS_MARKER = -629482886

    private val NULL_MARKER = Any()

    fun serialize(obj: Any?): ByteArray {
        val strings = LinkedHashMap<String, Int>()
        val strBuf = Bytes()
        val objBuf = Bytes()
        val queue = ArrayDeque<Any?>()
        queue.add(obj ?: NULL_MARKER)
        while (queue.isNotEmpty()) {
            write(queue.removeFirst(), strBuf, objBuf, strings, queue)
        }
        val out = Bytes()
        if (strBuf.size > 0) {
            out.i32(STRINGS_MARKER)
            out.i32(strBuf.size)
            out.raw(strBuf.toByteArray())
        }
        out.i32(OBJECTS_MARKER)
        out.i32(objBuf.size)
        out.raw(objBuf.toByteArray())
        return out.toByteArray()
    }

    private fun write(
        obj: Any?,
        strBuf: Bytes,
        objBuf: Bytes,
        strings: MutableMap<String, Int>,
        queue: ArrayDeque<Any?>,
    ) {
        when (obj) {
            NULL_MARKER -> objBuf.u8(NULL)
            is Boolean -> {
                objBuf.u8(BOOLEAN)
                objBuf.u8(if (obj) 1 else 0)
            }
            is Int -> {
                objBuf.u8(INT)
                objBuf.i32(obj)
            }
            is Float -> {
                objBuf.u8(FLOAT)
                objBuf.i32(obj.toRawBits())
            }
            is String -> {
                objBuf.u8(STRING)
                val offset = strings[obj] ?: strBuf.size.also { at ->
                    strings[obj] = at
                    val bytes = obj.encodeToByteArray()
                    strBuf.u16(bytes.size + 1)
                    strBuf.raw(bytes)
                    strBuf.u8(0)
                }
                objBuf.i32(offset)
            }
            is List<*> -> {
                objBuf.u8(ARRAY)
                objBuf.i32(obj.size)
                obj.forEach { queue.add(it ?: NULL_MARKER) }
            }
            is Map<*, *> -> {
                objBuf.u8(MAP)
                objBuf.i32(obj.size)
                obj.entries.forEach { (key, value) ->
                    queue.add(key ?: NULL_MARKER)
                    queue.add(value ?: NULL_MARKER)
                }
            }
            is Long -> {
                objBuf.u8(LONG)
                objBuf.i64(obj)
            }
            is Double -> {
                objBuf.u8(DOUBLE)
                objBuf.i64(obj.toRawBits())
            }
            is Char -> {
                objBuf.u8(CHAR)
                objBuf.i32(obj.code)
            }
            else -> throw IllegalArgumentException("Unsupported type ${obj?.let { it::class }} '$obj'")
        }
    }

    private class Bytes {
        private var buf = ByteArray(64)
        var size: Int = 0
            private set

        fun u8(value: Int) {
            ensure(1)
            buf[size++] = value.toByte()
        }

        fun u16(value: Int) {
            u8(value ushr 8)
            u8(value)
        }

        fun i32(value: Int) {
            u8(value ushr 24)
            u8(value ushr 16)
            u8(value ushr 8)
            u8(value)
        }

        fun i64(value: Long) {
            i32((value ushr 32).toInt())
            i32(value.toInt())
        }

        fun raw(bytes: ByteArray) {
            ensure(bytes.size)
            bytes.copyInto(buf, size)
            size += bytes.size
        }

        fun toByteArray(): ByteArray = buf.copyOf(size)

        private fun ensure(extra: Int) {
            if (size + extra <= buf.size) return
            var cap = buf.size
            while (cap < size + extra) cap *= 2
            buf = buf.copyOf(cap)
        }
    }
}
