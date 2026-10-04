package app.aaps.plugins.aps.openAPSAIMI.effects

import kotlin.test.Test
import kotlin.test.assertEquals

class AimiTickEffectEncodeTest {

    @Test
    fun setTbrAndSmbLinesAreStable() {
        val tbr = AimiTickEffect.SetTbr(
            rateUph = 2.5,
            durationMin = 30,
            overrideSafetyLimits = false,
            forceExact = true,
            adaptiveMultiplier = 1.0,
        )
        val smb = AimiTickEffect.Smb(units = 1.5, owner = "LegacyMealModes")
        assertEquals(
            "EFFECT SetTbr rate=2.50 dur=30 override=false forceExact=true adaptive=1.00",
            tbr.encode(),
        )
        assertEquals("EFFECT Smb units=1.50 owner=LegacyMealModes", smb.encode())
        assertEquals("READ key=DoubleKey.OApsAIMIMealPrebolus value=1.50", aimiTraceRead("DoubleKey.OApsAIMIMealPrebolus", "1.50"))
        assertEquals("WRITE key=AimiLongKey.LastPrebolusTime value=1700000000000", aimiTraceWrite("AimiLongKey.LastPrebolusTime", "1700000000000"))
        assertEquals("LOG hello", aimiTraceLog("hello"))
    }
}
