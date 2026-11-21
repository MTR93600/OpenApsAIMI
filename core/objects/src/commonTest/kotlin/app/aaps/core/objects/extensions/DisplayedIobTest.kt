package app.aaps.core.objects.extensions

import app.aaps.core.interfaces.aps.IobTotal
import kotlin.test.Test
import kotlin.test.assertEquals

class DisplayedIobTest {

    @Test
    fun displayedIobUnits_isRoundedBolusPlusBasal() {
        val bolus = IobTotal(time = 0, iob = 0.0014)
        val basal = IobTotal(time = 0, basaliob = 0.0014)

        assertEquals(0.002, displayedIobUnits(bolus, basal))
    }
}
