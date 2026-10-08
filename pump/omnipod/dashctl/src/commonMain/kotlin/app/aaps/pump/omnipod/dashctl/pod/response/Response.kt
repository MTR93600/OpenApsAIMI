package app.aaps.pump.omnipod.dashctl.pod.response

interface Response {

    val responseType: ResponseType
    val encoded: ByteArray
}
