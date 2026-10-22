package app.aaps.plugins.aps.openAPSAIMI.advisor.oref

import kotlin.test.Test
import kotlin.test.assertEquals

class OrefMlStatusTest {

    @Test
    fun unavailableDeviceMessageIsExplicit() {
        assertEquals(
            "ONNX risk scores are not available on this device.",
            OrefMlStatus.UNAVAILABLE_DEVICE.userMessage,
        )
    }
}
