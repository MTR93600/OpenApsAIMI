package app.aaps.pump.omnipod.dashctl.comm

import app.aaps.pump.omnipod.dashctl.pod.util.PodByteBuffer

/**
 * A 4 byte BLE node address: the controller id or the pod id.
 *
 * Port of the Android `app.aaps.pump.omnipod.common.bledriver.comm.Id`.
 * Difference from Android: [equals] checks `other !is Id` instead of comparing `javaClass`,
 * because `javaClass` does not exist in common code. `Id` is final, so the result is the same.
 */
data class Id(val address: ByteArray) {

    init {
        require(address.size == 4)
    }

    /**
     * Used to obtain podId from controllerId
     * The original PDM seems to rotate over 3 Ids:
     * controllerID+1, controllerID+2 and controllerID+3
     */
    fun increment(): Id {
        val nodeId = address.copyOf()
        nodeId[3] = (nodeId[3].toInt() and -4).toByte()
        nodeId[3] = (nodeId[3].toInt() or PERIPHERAL_NODE_INDEX).toByte()
        return Id(nodeId)
    }

    override fun toString(): String {
        val asInt = PodByteBuffer.wrap(address).int
        return "$asInt/${address.toCommLogHex()}"
    }

    fun toLong(): Long {
        return PodByteBuffer.wrap(address).int.toLong() and 0xffffffffL
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Id) return false

        return address.contentEquals(other.address)
    }

    override fun hashCode(): Int {
        return address.contentHashCode()
    }

    companion object {

        private const val PERIPHERAL_NODE_INDEX = 1

        fun fromInt(v: Int): Id {
            return Id(PodByteBuffer.allocate(4).putInt(v).array())
        }

        /** Keeps the low 4 bytes of [v], big-endian, like `ByteBuffer.putLong(v)` then bytes 4..7. */
        fun fromLong(v: Long): Id {
            val bytes = ByteArray(4)
            bytes[0] = (v shr 24).toByte()
            bytes[1] = (v shr 16).toByte()
            bytes[2] = (v shr 8).toByte()
            bytes[3] = v.toByte()
            return Id(bytes)
        }
    }
}
