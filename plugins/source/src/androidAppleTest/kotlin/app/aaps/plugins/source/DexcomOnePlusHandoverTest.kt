package app.aaps.plugins.source

import app.aaps.core.keys.interfaces.AppPlatform
import app.aaps.plugins.dexcomoneplus.identity.OnePlusSensorIdentity
import app.aaps.plugins.dexcomoneplus.identity.OnePlusStoredSession
import app.aaps.plugins.dexcomoneplus.parse.OnePlusExtendedVersionRx
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DexcomOnePlusHandoverTest {

    private val session = OnePlusStoredSession(
        identity = OnePlusSensorIdentity(pin = "4821", serial = "SN123456", gtin = "0123", rawGs1 = "raw"),
        lastMac = "AA:BB:CC:DD:EE:FF",
        lastDeviceName = "DXCMAB",
        sharedKey = ByteArray(16) { it.toByte() },
    )
    private val info = OnePlusExtendedVersionRx(sessionLengthSeconds = 1_339_200, warmupSeconds = 3_720)

    private fun export(from: AppPlatform) = DexcomOnePlusHandover.export(session, 1_700_000_000_000L, info, from)

    @Test
    fun `android to android keeps the address and everything else`() {
        val taken = assertNotNull(DexcomOnePlusHandover.parse(export(AppPlatform.Android), AppPlatform.Android))

        assertEquals("AA:BB:CC:DD:EE:FF", taken.session.lastMac)
        assertEquals(session.identity, taken.session.identity)
        assertEquals("DXCMAB", taken.session.lastDeviceName)
        assertContentEquals(session.sharedKey, taken.session.sharedKey)
        assertEquals(1_700_000_000_000L, taken.sessionStartMs)
        assertEquals(info, taken.sensorInfo)
    }

    @Test
    fun `an address never crosses to or from an iPhone`() {
        assertNull(DexcomOnePlusHandover.parse(export(AppPlatform.Android), AppPlatform.Ios)!!.session.lastMac)
        assertNull(DexcomOnePlusHandover.parse(export(AppPlatform.Ios), AppPlatform.Android)!!.session.lastMac)
        // An iPhone's identifier is made for that one phone, so not even iPhone to iPhone.
        assertNull(DexcomOnePlusHandover.parse(export(AppPlatform.Ios), AppPlatform.Ios)!!.session.lastMac)
    }

    @Test
    fun `without the address the name and the code still come along`() {
        val taken = DexcomOnePlusHandover.parse(export(AppPlatform.Android), AppPlatform.Ios)!!

        assertEquals("4821", taken.session.identity.pin)
        assertEquals("DXCMAB", taken.session.lastDeviceName)
    }

    @Test
    fun `no pairing code means no sensor`() {
        assertNull(DexcomOnePlusHandover.parse(export(AppPlatform.Android) - "pin", AppPlatform.Android))
        assertNull(DexcomOnePlusHandover.parse(export(AppPlatform.Android) + ("pin" to "12"), AppPlatform.Android))
    }
}
