package app.aaps.pump.danaR.comm

import app.aaps.core.interfaces.pump.BolusProgressData
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.pump.danar.comm.MsgBolusProgress
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.`when`

class MsgBolusProgressTest : DanaRTestBase() {

    @Test fun runTest() {
        `when`(rh.gs(ArgumentMatchers.eq(app.aaps.core.ui.R.string.bolus_delivering), ArgumentMatchers.anyDouble())).thenReturn("Delivering %1\$.2fU")
        danaPump.bolusingDetailedBolusInfo = DetailedBolusInfo().also { it.insulin = 3.0 }
        val packet = MsgBolusProgress(injector)

        // test message decoding
        val array = ByteArray(100)
        putIntToArray(array, 0, 2 * 100)
        packet.handleMessage(array)
        Assertions.assertEquals(1.0, BolusProgressData.delivered, 0.0)
    }
}