package app.aaps.ios.shell.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two iOS apps: the master (Xcode target AAPS) and the follower (target AAPS2, shown as
 * AAPSClient). Each target sets the `AAPSRole` Info.plist key; [IosConfig] turns it into the same
 * flags as the Android `full` and `aapsclient` flavours.
 */
class IosConfigRoleTest {

    /** A test binary has no `AAPSRole` key, so this is also what the master app had before the key. */
    @Test
    fun `without the key the app is the master`() {
        assertEquals(IosAppRole.MASTER, IosAppRole.fromValue(null))
        assertEquals(IosAppRole.MASTER, IosAppRole.fromBundle())
        assertEquals(IosAppRole.MASTER, IosConfig().role)
    }

    @Test
    fun `only the exact follower value makes a follower`() {
        assertEquals(IosAppRole.CLIENT, IosAppRole.fromValue("aapsclient"))
        assertEquals(IosAppRole.CLIENT, IosAppRole.fromValue(" AAPSClient "))
        assertEquals(IosAppRole.MASTER, IosAppRole.fromValue("master"))
        assertEquals(IosAppRole.MASTER, IosAppRole.fromValue(""))
        // An unset build setting reaches the Info.plist as the literal text, or as nothing.
        assertEquals(IosAppRole.MASTER, IosAppRole.fromValue("\$(AAPS_ROLE)"))
    }

    @Test
    fun `the master has the values of the Android full flavour`() {
        val config = IosConfig(extraDir = null, role = IosAppRole.MASTER)

        assertTrue(config.APS)
        assertTrue(config.PUMPDRIVERS)
        assertFalse(config.PUMPCONTROL)
        assertFalse(config.AAPSCLIENT)
        assertFalse(config.AAPSCLIENT1)
        assertFalse(config.AAPSCLIENT2)
        assertFalse(config.AAPSCLIENT3)
        assertEquals("full", config.FLAVOR)
    }

    @Test
    fun `the follower has the values of the Android aapsclient flavour`() {
        val config = IosConfig(extraDir = null, role = IosAppRole.CLIENT)

        assertFalse(config.APS)
        assertFalse(config.PUMPDRIVERS)
        assertFalse(config.PUMPCONTROL)
        assertTrue(config.AAPSCLIENT)
        assertTrue(config.AAPSCLIENT1)
        assertFalse(config.AAPSCLIENT2)
        assertFalse(config.AAPSCLIENT3)
        assertEquals("aapsclient", config.FLAVOR)
        // A follower never runs the loop, so the engineering-mode gate does not apply, as on Android.
        assertTrue(config.isEngineeringModeOrRelease())
    }
}
