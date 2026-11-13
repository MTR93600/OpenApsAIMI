package app.aaps.pump.omnipod.dashctl.comm.session

import app.aaps.pump.omnipod.dashctl.crypto.DashNonce

/**
 * Port of the Android `app.aaps.pump.omnipod.common.bledriver.comm.session.SessionKeys`.
 * Only change: the nonce is [DashNonce], which gives the same bytes as the Android `Nonce`.
 */
sealed class SessionNegotiationResponse

data class SessionKeys(
    val ck: ByteArray,
    val nonce: DashNonce,
    var msgSequenceNumber: Byte
) : SessionNegotiationResponse() {

    init {
        require(ck.size == 16) { "CK has to be 16 bytes long" }
    }
}

data class SessionNegotiationResynchronization(
    val synchronizedEapSqn: EapSqn,
    val msgSequenceNumber: Byte
) : SessionNegotiationResponse()
