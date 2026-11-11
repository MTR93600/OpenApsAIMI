package app.aaps.plugins.sync.garmin

import kotlin.io.encoding.Base64

/** Efficient encoding for glucose/timestamp pairs.
 *
 * Garmin devices don't have much memory when deserializing received JSON messages.
 * In particular older devices my kill our app when we send 2h of glucose values. Therefore, we
 * encode the values efficiently.
 * We use [var encoding](https://en.wikipedia.org/wiki/Variable-width_encoding). In order to
 * keep timestamps small, we encode the difference to the previous pair and to encode negative values
 * efficiently, we use [zig-zag encoding](https://en.wikipedia.org/wiki/Variable-length_quantity).
 *
 * Shared code: the iPhone sends the watch the same bytes as Android. The bytes are little endian,
 * which is what the watch app reads.
 */
class DeltaVarEncodedList {
    private var lastValues: IntArray
    private var data: ByteArray
    private val start: Int = 0
    private var end: Int = 0

    val byteSize: Int get() =  end - start
    var size: Int = 0
        private set

    /** Creates a new list of given size.
     *
     * @param byteSize How large the internal buffer should be. The buffer doesn't grow
     * automatically, so you need to set it large enough.
     * @param entrySize Size of each entry (e.g. 2 for glucose+timestamp). Delta is computed on each
     * entrySize value.
     */
    constructor(byteSize: Int, entrySize: Int) {
        data = ByteArray(toLongBoundary(byteSize))
        lastValues = IntArray(entrySize)
    }

    /** Creates a list from encoded values.
     *
     * @param lastValues the last values of the list. Needs to be entrySize long.
     * @param encoded the encoded data
     */
    constructor(lastValues: IntArray, encoded: ByteArray) {
        this.lastValues = lastValues
        data = encoded.copyOf()
        end = data.size
        val it = DeltaIterator()
        while (it.next()) {
            size++
        }
    }

    /** Gets the encoded data, as little endian 64 bit words. */
    fun encodedData(): List<Long> {
        val padded = data.copyOf(toLongBoundary(end))
        return List(padded.size / 8) { word ->
            var value = 0L
            for (b in 7 downTo 0) value = (value shl 8) or (padded[word * 8 + b].toLong() and 0xff)
            value
        }
    }

    fun encodedBase64(): String = Base64.Default.encode(data, start, end)

    private fun addVarEncoded(value: Int) {
        var remaining: Int = value
        do {
            // Grow data if needed (double size).
            if (end == data.size) {
                data = data.copyOf(maxOf(2 * end, 8))
            }
            if ((remaining and 0x7f.inv()) != 0) {
                data[end++] = ((remaining and 0x7f) or 0x80).toByte()
            } else {
                data[end++] = remaining.toByte()
            }
            remaining = remaining ushr 7
        } while (remaining != 0)
    }

    private fun addI(value: Int, idx: Int) {
        val delta: Int = value - lastValues[idx]
        addVarEncoded(zigzagEncode(delta))
        lastValues[idx] = value
    }

    /** Adds an entry to the buffer.
     *
     * [values] length must be the same as entrySize provided in the constructor.  */
    fun add(vararg values: Int) {
        if (values.size != lastValues.size) {
            throw IllegalArgumentException()
        }
        for (idx in values.indices) {
            addI(values[idx], idx)
        }
        size++
    }

    fun toArray(): IntArray {
        val values = IntArray(lastValues.size * size)
        var count = 0
        val it = DeltaIterator()
        while (it.next()) {
            for (v in it.current()) values[count++] = v
        }
        val next: IntArray = lastValues.copyOf(lastValues.size)
        var nextIdx: Int = next.size - 1
        for (valueIdx in count - 1 downTo 0) {
            val value: Int = values[valueIdx]
            values[valueIdx] = next[nextIdx]
            next[nextIdx] -= value
            nextIdx = (nextIdx + 1) % next.size
        }
        return values
    }

    private inner class DeltaIterator {

        /** Read position in [data], from [start] to [end]. */
        private var position = start
        private val currentValues: IntArray = IntArray(lastValues.size)
        private var more: Boolean = false
        fun current(): IntArray {
            return currentValues
        }

        private fun readNext(): Int {
            var v = 0
            var offset = 0
            var b: Int
            do {
                if (position >= end) {
                    more = false
                    return 0
                }
                b = data[position++].toInt()
                v = v or ((b and 0x7f) shl offset)
                offset += 7
            } while ((b and 0x80) != 0)
            return zigzagDecode(v)
        }

        operator fun next(): Boolean {
            if (position >= end) return false
            more = true
            var i = 0
            while (i < currentValues.size && more) {
                currentValues[i] = readNext()
                i++
            }
            return more
        }
    }

    companion object {

        private fun toLongBoundary(i: Int): Int {
            return 8 * ((i + 7) / 8)
        }

        private fun zigzagEncode(i: Int): Int {
            return (i shr 31) xor (i shl 1)
        }

        private fun zigzagDecode(i: Int): Int {
            return (i ushr 1) xor -(i and 1)
        }
    }
}