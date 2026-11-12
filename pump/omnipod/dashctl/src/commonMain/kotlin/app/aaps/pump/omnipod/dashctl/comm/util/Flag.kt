package app.aaps.pump.omnipod.dashctl.comm.util

/**
 * One byte of bit flags. Index 0 is the most significant bit, index 7 the least significant.
 *
 * Port of the Android `app.aaps.pump.omnipod.common.bledriver.util.Flag`, unchanged.
 */
class Flag(var value: Int = 0) {

    fun set(idx: Byte, set: Boolean) {
        val mask = 1 shl (7 - idx)
        if (!set) return
        value = value or mask
    }

    fun get(idx: Byte): Int {
        val mask = 1 shl (7 - idx)
        if (value and mask == 0) {
            return 0
        }
        return 1
    }
}
