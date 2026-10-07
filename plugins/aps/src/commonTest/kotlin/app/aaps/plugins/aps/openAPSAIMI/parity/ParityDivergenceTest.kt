package app.aaps.plugins.aps.openAPSAIMI.parity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ParityDivergenceTest {

    @Test
    fun oneUlpOnTheRateIsTheFirstField() {
        val android = sample(tbr = 1.0)
        val ios = sample(tbr = Double.fromBits(1.0.toRawBits() + 1))
        assertEquals(
            "scenario=planted field=tbrRate android=${1.0.bits()} ios=${ios.tbrRateUph.bits()}",
            firstParityDivergence(android.canonical(), ios.canonical()),
        )
    }

    @Test
    fun theSameBytesReportNoField() {
        val trace = sample(tbr = 1.0)
        assertNull(firstParityDivergence(trace.canonical(), trace.canonical()))
    }

    private fun sample(tbr: Double) = ParityDoseTrace(
        scenario = "planted",
        tbrRateUph = tbr,
        tbrMinutes = 30,
        smbU = 0.0,
        eventualBg = null,
        isfMgdl = null,
        virtualCobG = 0.0,
        modelWord0 = null,
        healthKit = "absent",
        bgMgdl = 110.0,
        deltaMgdl = 0.0,
        port = "planted",
    )
}
