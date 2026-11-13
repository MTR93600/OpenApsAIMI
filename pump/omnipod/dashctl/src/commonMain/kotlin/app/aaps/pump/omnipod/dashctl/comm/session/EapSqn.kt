package app.aaps.pump.omnipod.dashctl.comm.session

/**
 * The 6 byte EAP-AKA sequence number (SQN), big-endian.
 *
 * Port of the Android `app.aaps.pump.omnipod.common.bledriver.comm.session.EapSqn`. Only change:
 * `java.nio.ByteBuffer` is replaced by shifts that give the same big-endian bytes.
 */
class EapSqn(val value: ByteArray) {
    constructor(v: Long) : this(fromLong(v))

    init {
        require(value.size == SIZE) { "Eap SQN is $SIZE bytes long" }
    }

    fun increment(): EapSqn {
        return EapSqn(toLong() + 1)
    }

    /** Same as Android `ByteBuffer.wrap(00 00 + value).long`. */
    fun toLong(): Long {
        var ret = 0L
        for (b in value) {
            ret = (ret shl 8) or (b.toLong() and 0xff)
        }
        return ret
    }

    override fun toString(): String {
        return "EapSqn(value=${toLong()})"
    }

    companion object {

        private const val SIZE = 6

        /** Same as Android `ByteBuffer.allocate(8).putLong(v).array().copyOfRange(2, 8)`. */
        private fun fromLong(v: Long): ByteArray {
            return ByteArray(SIZE) { i -> (v shr (8 * (SIZE - 1 - i))).toByte() }
        }
    }
}
