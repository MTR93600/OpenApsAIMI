package app.aaps.plugins.dexcomoneplus.identity

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The iPhone side of a sensor slot's own store, on a real `NSUserDefaults` suite.
 *
 * The thing worth proving is the difference this class has to hide: `NSUserDefaults` answers `0`
 * and `false` for a key that was never written, where the Android store answers the caller's
 * default. A slot that had never recorded its soak start would otherwise read as "started at the
 * epoch", which is a settled sensor and would let a pre-soak be promoted without soaking.
 *
 * No test name contains a comma - Kotlin/Native rejects a backtick name that does.
 */
class OnePlusSensorPrefsIosTest {

    private val prefs = OnePlusSensorPrefsIos(SUITE)

    @BeforeTest
    fun clean() = prefs.edit { clear() }

    @AfterTest
    fun cleanAfter() = prefs.edit { clear() }

    @Test
    fun `an absent key answers the default and not zero`() {
        assertNull(prefs.getString("missing"))
        assertEquals(-1L, prefs.getLong("missing", -1L))
        assertEquals(7, prefs.getInt("missing", 7))
        assertTrue(prefs.getBoolean("missing", true))
        assertFalse(prefs.contains("missing"))
    }

    @Test
    fun `a value written is the value read back`() {
        prefs.edit {
            putString("pin", "1234")
            putLong("start", 1_700_000_000_000L)
            putInt("count", 42)
            putBoolean("done", true)
        }
        assertEquals("1234", prefs.getString("pin"))
        assertEquals(1_700_000_000_000L, prefs.getLong("start", -1L))
        assertEquals(42, prefs.getInt("count", -1))
        assertTrue(prefs.getBoolean("done", false))
        assertTrue(prefs.contains("pin"))
    }

    @Test
    fun `a stored zero is told apart from an absent key`() {
        prefs.edit { putLong("start", 0L) }
        assertTrue(prefs.contains("start"))
        assertEquals(0L, prefs.getLong("start", -1L))
    }

    @Test
    fun `writing a null string removes the key`() {
        prefs.edit { putString("serial", "ABC") }
        assertEquals("ABC", prefs.getString("serial"))
        prefs.edit { putString("serial", null) }
        assertNull(prefs.getString("serial"))
        assertFalse(prefs.contains("serial"))
    }

    @Test
    fun `remove takes one key away and leaves the rest`() {
        prefs.edit {
            putString("pin", "1234")
            putString("serial", "ABC")
        }
        prefs.edit { remove("serial") }
        assertEquals("1234", prefs.getString("pin"))
        assertNull(prefs.getString("serial"))
    }

    @Test
    fun `clear empties the slot`() {
        prefs.edit {
            putString("pin", "1234")
            putLong("start", 5L)
        }
        prefs.edit { clear() }
        assertNull(prefs.getString("pin"))
        assertEquals(-1L, prefs.getLong("start", -1L))
    }

    @Test
    fun `two slots do not see each other`() {
        val other = OnePlusSensorPrefsIos(OTHER_SUITE)
        other.edit { clear() }
        prefs.edit { putString("pin", "1111") }
        other.edit { putString("pin", "2222") }
        assertEquals("1111", prefs.getString("pin"))
        assertEquals("2222", other.getString("pin"))
        other.edit { clear() }
    }

    companion object {

        /** Test-only suite names, so a run never touches the names the real slots use. */
        private const val SUITE = "oneplus_sensor_prefs_ios_test"
        private const val OTHER_SUITE = "oneplus_sensor_prefs_ios_test_other"
    }
}
