package app.aaps.pump.omnipod.dashctl.pod.response

import app.aaps.pump.omnipod.dashctl.pod.response.ResponseType.ActivationResponseType

abstract class ActivationResponseBase(
    val activationResponseType: ActivationResponseType,
    encoded: ByteArray
) : ResponseBase(ResponseType.ACTIVATION_RESPONSE, encoded)
