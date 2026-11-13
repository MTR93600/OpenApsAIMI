package app.aaps.pump.omnipod.dashctl.comm.pair

import app.aaps.pump.omnipod.dashctl.comm.toCommLogHex

/**
 * Port of the Android `app.aaps.pump.omnipod.common.bledriver.comm.pair.PairResult`, unchanged.
 * [msgSeq] is the outer message counter after pairing. The first session set up starts from it.
 */
data class PairResult(val ltk: ByteArray, val msgSeq: Byte) {
    init {
        require(ltk.size == 16) { "LTK length must be 16 bytes. Received LTK: ${ltk.toCommLogHex()}" }
    }
}
