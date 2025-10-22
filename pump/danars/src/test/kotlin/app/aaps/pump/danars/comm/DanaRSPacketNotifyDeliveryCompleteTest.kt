package app.aaps.pump.danars.comm

import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.pump.danars.DanaRSTestBase
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.mockito.Mockito.anyDouble
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.`when`

class DanaRSPacketNotifyDeliveryCompleteTest : DanaRSTestBase() {

    @Test
    fun runTest() {
        `when`(rh.gs(anyInt(), anyDouble())).thenReturn("SomeString")

        danaPump.bolusingDetailedBolusInfo = DetailedBolusInfo()
        val packet = DanaRSPacketNotifyDeliveryComplete(aapsLogger, rh, rxBus, danaPump)
        // test params
        Assertions.assertEquals(0, packet.getRequestParams().size)
        // test message decoding
        packet.handleMessage(createArray(17, 0.toByte()))
        Assertions.assertEquals(true, danaPump.bolusDone)
        Assertions.assertEquals("NOTIFY__DELIVERY_COMPLETE", packet.friendlyName)
    }
}