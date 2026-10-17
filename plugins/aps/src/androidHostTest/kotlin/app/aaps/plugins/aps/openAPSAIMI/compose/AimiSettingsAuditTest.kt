package app.aaps.plugins.aps.openAPSAIMI.compose

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.BooleanNonPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey
import app.aaps.core.keys.interfaces.IntNonPreferenceKey
import app.aaps.core.keys.interfaces.LongNonPreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.StringNonPreferenceKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.plugins.aps.openAPSAIMI.keys.AimiLongKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Tests for [AimiSettingsAudit], the pure object behind the settings audit screen.
 *
 * Every test rests on one baseline: a key nobody stubs has nothing stored, so it contributes no
 * row. A plain Mockito mock does NOT give that. For a `Boolean?`, `Double?`, `Int?` or `Long?`
 * return it answers `false` or `0`, not `null`, and the whole manifest then looks "changed". So
 * each `getIfExists` overload the audit calls is stubbed to `null` in `setUp`. A stub a test
 * writes later is more specific, so it still wins.
 *
 * It lives in `androidHostTest` and not in `commonTest` because it needs a mock of [Preferences],
 * and Mockito is JVM only.
 */
class AimiSettingsAuditTest {

    private val preferences: Preferences = mock()

    private val strings = object : AimiSettingsAuditStrings {
        override val changedByLoop: TextRef = TextRef.AndroidRes(1)
        override val changedBySlider: TextRef = TextRef.AndroidRes(2)
        override val changedByPreset: TextRef = TextRef.AndroidRes(3)
        override val changedByUser: TextRef = TextRef.AndroidRes(4)
        override val yes: TextRef = TextRef.AndroidRes(5)
        override val no: TextRef = TextRef.AndroidRes(6)
    }

    init {
        whenever(preferences.getIfExists(any<BooleanNonPreferenceKey>())).thenReturn(null)
        whenever(preferences.getIfExists(any<DoublePreferenceKey>())).thenReturn(null)
        whenever(preferences.getIfExists(any<IntNonPreferenceKey>())).thenReturn(null)
        whenever(preferences.getIfExists(any<StringNonPreferenceKey>())).thenReturn(null)
        whenever(preferences.getIfExists(any<LongNonPreferenceKey>())).thenReturn(null)
    }

    private fun build() = AimiSettingsAudit.build(preferences, strings)

    @Test
    fun `a row appears only when stored differs from default`() {
        // OApsAIMIMLtraining defaults to false. Stored at the same value: no row.
        whenever(preferences.getIfExists(BooleanKey.OApsAIMIMLtraining)).thenReturn(false)
        assertFalse(BooleanKey.OApsAIMIMLtraining.key in build().rows.map { it.keyString })

        // Stored at a different value: a row appears.
        whenever(preferences.getIfExists(BooleanKey.OApsAIMIMLtraining)).thenReturn(true)
        assertTrue(BooleanKey.OApsAIMIMLtraining.key in build().rows.map { it.keyString })
    }

    @Test
    fun `a key with no stored value at all produces no row`() {
        assertTrue(build().rows.isEmpty())
    }

    @Test
    fun `a secret key's values are never in the rendered row model`() {
        whenever(preferences.getIfExists(StringKey.AimiEmergencySosPhone)).thenReturn("+15551234567")

        val row = build().rows.single { it.keyString == StringKey.AimiEmergencySosPhone.key }

        assertTrue(row.secret)
        assertNull(row.storedValue)
        assertNull(row.defaultValue)
        assertTrue(row.isSet)
    }

    @Test
    fun `the writer wording is right for each writer combination including USER plus LOOP`() {
        // LOOP only.
        whenever(preferences.getIfExists(BooleanKey.OApsAIMIPkpdPragmaticReliefEnabled)).thenReturn(false)
        // SLIDER only.
        whenever(preferences.getIfExists(BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled)).thenReturn(false)
        // PRESET only.
        whenever(preferences.getIfExists(DoubleKey.OApsAIMIPkpdInitialDiaH)).thenReturn(8.0)
        // USER only.
        whenever(preferences.getIfExists(BooleanKey.OApsAIMIMLtraining)).thenReturn(true)
        // USER and LOOP together: LOOP must still win the wording.
        whenever(preferences.getIfExists(DoubleKey.AimiTubeHypoFloorMgdl)).thenReturn(60.0)

        val rows = build().rows.associateBy { it.keyString }

        assertEquals(AimiSettingsAuditChangedBy.LOOP, rows.getValue(BooleanKey.OApsAIMIPkpdPragmaticReliefEnabled.key).changedBy)
        assertEquals(AimiSettingsAuditChangedBy.SLIDER, rows.getValue(BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled.key).changedBy)
        assertEquals(AimiSettingsAuditChangedBy.PRESET, rows.getValue(DoubleKey.OApsAIMIPkpdInitialDiaH.key).changedBy)
        assertEquals(AimiSettingsAuditChangedBy.USER, rows.getValue(BooleanKey.OApsAIMIMLtraining.key).changedBy)
        assertEquals(AimiSettingsAuditChangedBy.LOOP, rows.getValue(DoubleKey.AimiTubeHypoFloorMgdl.key).changedBy)
    }

    @Test
    fun `the reset restores the shipped default in every type branch`() {
        // One key per branch of `readDiff`. Writing 0.0, an empty string or 0L instead of the
        // key's own default in any branch would be a wrong dosing value.
        whenever(preferences.getIfExists(BooleanKey.OApsAIMIMLtraining)).thenReturn(true)
        whenever(preferences.getIfExists(DoubleKey.OApsAIMIweight)).thenReturn(83.0)
        whenever(preferences.getIfExists(IntKey.OApsAIMILunchinterval)).thenReturn(99)
        whenever(preferences.getIfExists(StringKey.OApsAIMIWCycleTrackingMode)).thenReturn("something")
        whenever(preferences.getIfExists(AimiLongKey.LastPrebolusTime)).thenReturn(1_700_000_000_000L)

        val rows = build().rows.associateBy { it.keyString }
        listOf(
            BooleanKey.OApsAIMIMLtraining.key,
            DoubleKey.OApsAIMIweight.key,
            IntKey.OApsAIMILunchinterval.key,
            StringKey.OApsAIMIWCycleTrackingMode.key,
            AimiLongKey.LastPrebolusTime.key,
        ).forEach { rows.getValue(it).resetToDefault(preferences) }

        verify(preferences).put(BooleanKey.OApsAIMIMLtraining, BooleanKey.OApsAIMIMLtraining.defaultValue)
        verify(preferences).put(DoubleKey.OApsAIMIweight, DoubleKey.OApsAIMIweight.defaultValue)
        verify(preferences).put(IntKey.OApsAIMILunchinterval, IntKey.OApsAIMILunchinterval.defaultValue)
        verify(preferences).put(StringKey.OApsAIMIWCycleTrackingMode, StringKey.OApsAIMIWCycleTrackingMode.defaultValue)
        verify(preferences).put(AimiLongKey.LastPrebolusTime, AimiLongKey.LastPrebolusTime.defaultValue)
    }

    @Test
    fun `a preference key row carries a real title`() {
        // Adapted from the old "no zero title" test. The old row held an Int resource id where 0
        // meant "no title" and crashed the screen. On this branch PreferenceKey.title is a
        // required TextRef, so a missing title cannot be built. What can still go wrong is an
        // AndroidRes(0) placeholder, so that is what is checked.
        whenever(preferences.getIfExists(DoubleKey.OApsAIMIIsfFusionMaxChangePerTick)).thenReturn(0.4)

        val report = build()

        val row = report.rows.single { it.keyString == DoubleKey.OApsAIMIIsfFusionMaxChangePerTick.key }
        assertNotNull(row.title)
        assertNotEquals(TextRef.AndroidRes(0), row.title)
    }

    @Test
    fun `every setting in scope can be compared so none is silently dropped`() {
        // The audit dispatches on the key's interface and returns null for a type it does not know.
        // Every key is made to differ from its default and every one must produce a row.
        whenever(preferences.getIfExists(any<BooleanNonPreferenceKey>())).thenAnswer { !(it.arguments[0] as BooleanNonPreferenceKey).defaultValue }
        whenever(preferences.getIfExists(any<DoublePreferenceKey>())).thenAnswer { (it.arguments[0] as DoublePreferenceKey).defaultValue + 1.0 }
        whenever(preferences.getIfExists(any<IntNonPreferenceKey>())).thenAnswer { (it.arguments[0] as IntNonPreferenceKey).defaultValue + 1 }
        whenever(preferences.getIfExists(any<StringNonPreferenceKey>())).thenAnswer { (it.arguments[0] as StringNonPreferenceKey).defaultValue + "x" }
        whenever(preferences.getIfExists(any<LongNonPreferenceKey>())).thenAnswer { (it.arguments[0] as LongNonPreferenceKey).defaultValue + 1L }

        val report = build()

        assertEquals(report.scopedCount, report.rows.size)
    }

    @Test
    fun `two values that differ are never shown as the same text`() {
        // The shared formatter rounds to two decimals, so 0.0305 and 0.03 would both read "0.03"
        // while the row claims they differ.
        whenever(preferences.getIfExists(DoubleKey.OApsAIMIIsfFusionMaxChangePerTick)).thenReturn(0.0305)

        val row = build().rows.single { it.keyString == DoubleKey.OApsAIMIIsfFusionMaxChangePerTick.key }

        assertNotEquals(row.defaultValue?.valueText, row.storedValue?.valueText)
    }

    @Test
    fun `a stored value the app does not read back is marked as not used`() {
        // Simple mode makes `get` return the shipped default for a `defaultedBySM` key and ignore
        // what is stored.
        whenever(preferences.getIfExists(IntKey.OApsAIMILunchinterval)).thenReturn(8)
        whenever(preferences.get(IntKey.OApsAIMILunchinterval)).thenReturn(IntKey.OApsAIMILunchinterval.defaultValue)
        whenever(preferences.getIfExists(BooleanKey.OApsAIMIMLtraining)).thenReturn(true)
        whenever(preferences.get(BooleanKey.OApsAIMIMLtraining)).thenReturn(true)

        val rows = build().rows.associateBy { it.keyString }

        assertTrue(rows.getValue(IntKey.OApsAIMILunchinterval.key).storedValueIgnored)
        assertFalse(rows.getValue(BooleanKey.OApsAIMIMLtraining.key).storedValueIgnored)
    }

    @Test
    fun `a key only the loop writes is shown but offers no reset`() {
        whenever(preferences.getIfExists(AimiLongKey.LastPrebolusTime)).thenReturn(1_700_000_000_000L)
        whenever(preferences.getIfExists(BooleanKey.OApsAIMIMLtraining)).thenReturn(true)

        val rows = build().rows.associateBy { it.keyString }

        assertTrue(rows.getValue(AimiLongKey.LastPrebolusTime.key).runtimeState)
        assertFalse(rows.getValue(BooleanKey.OApsAIMIMLtraining.key).runtimeState)
    }

    @Test
    fun `the count line matches the rows`() {
        whenever(preferences.getIfExists(BooleanKey.OApsAIMIMLtraining)).thenReturn(true)
        whenever(preferences.getIfExists(DoubleKey.OApsAIMIPkpdInitialDiaH)).thenReturn(8.0)

        val report = build()

        assertEquals(2, report.changedCount)
        // Every manifest entry is in scope except the one BOOKKEEPING-only key (the setup wizard flag).
        assertEquals(AimiSettingsManifest.entries.size - 1, report.scopedCount)
    }
}
