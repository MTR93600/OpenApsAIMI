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
import app.aaps.plugins.aps.openAPSAIMI.keys.AimiLongKey
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

/**
 * Tests for [AimiSettingsAudit], the pure object behind the settings audit screen.
 *
 * Every test here rests on one baseline: a key nobody stubs has nothing stored, so it contributes
 * no row. `relaxed = true` alone does NOT give that. A relaxed mock answers a call it was never
 * told about with the empty value of the return type, and for `Boolean?`, `Double?` or `String?`
 * that is `false`, `0.0` and `""` — not `null`. The whole manifest then looks "changed": the first
 * version of this file asked for 2 rows and got 187. So each `getIfExists` overload the audit calls
 * is stubbed to `null` below, and `relaxed` is kept only for the `put` the reset test verifies. A
 * stub a test writes later is more specific, so it still wins.
 */
class AimiSettingsAuditTest {

    private val preferences = mockk<Preferences>(relaxed = true)

    init {
        every { preferences.getIfExists(any<BooleanNonPreferenceKey>()) } returns null
        every { preferences.getIfExists(any<DoublePreferenceKey>()) } returns null
        every { preferences.getIfExists(any<IntNonPreferenceKey>()) } returns null
        every { preferences.getIfExists(any<StringNonPreferenceKey>()) } returns null
        every { preferences.getIfExists(any<LongNonPreferenceKey>()) } returns null
    }

    @Test
    fun `a row appears only when stored differs from default`() {
        // OApsAIMIMLtraining defaults to false. Stored at the same value: no row.
        every { preferences.getIfExists(BooleanKey.OApsAIMIMLtraining) } returns false
        val unchanged = AimiSettingsAudit.build(preferences)
        assertThat(unchanged.rows.map { it.keyString }).doesNotContain(BooleanKey.OApsAIMIMLtraining.key)

        // Stored at a different value: a row appears.
        every { preferences.getIfExists(BooleanKey.OApsAIMIMLtraining) } returns true
        val changed = AimiSettingsAudit.build(preferences)
        assertThat(changed.rows.map { it.keyString }).contains(BooleanKey.OApsAIMIMLtraining.key)
    }

    @Test
    fun `a key with no stored value at all produces no row`() {
        // Nothing is stubbed on top of the null baseline set in `init`, so nothing is stored.
        val report = AimiSettingsAudit.build(preferences)
        assertThat(report.rows).isEmpty()
    }

    @Test
    fun `a secret key's values are never in the rendered row model`() {
        every { preferences.getIfExists(StringKey.AimiEmergencySosPhone) } returns "+15551234567"

        val report = AimiSettingsAudit.build(preferences)
        val row = report.rows.single { it.keyString == StringKey.AimiEmergencySosPhone.key }

        assertThat(row.secret).isTrue()
        assertThat(row.storedValue).isNull()
        assertThat(row.defaultValue).isNull()
        assertThat(row.isSet).isTrue()
    }

    @Test
    fun `the writer wording is right for each writer combination including USER plus LOOP`() {
        // LOOP only.
        every { preferences.getIfExists(BooleanKey.OApsAIMIPkpdPragmaticReliefEnabled) } returns false
        // SLIDER only.
        every { preferences.getIfExists(BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled) } returns false
        // PRESET only.
        every { preferences.getIfExists(DoubleKey.OApsAIMIPkpdInitialDiaH) } returns 8.0
        // USER only.
        every { preferences.getIfExists(BooleanKey.OApsAIMIMLtraining) } returns true
        // USER and LOOP together: LOOP must still win the wording.
        every { preferences.getIfExists(DoubleKey.AimiTubeHypoFloorMgdl) } returns 60.0

        val rows = AimiSettingsAudit.build(preferences).rows.associateBy { it.keyString }

        assertThat(rows.getValue(BooleanKey.OApsAIMIPkpdPragmaticReliefEnabled.key).changedBy)
            .isEqualTo(AimiSettingsAuditChangedBy.LOOP)
        assertThat(rows.getValue(BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled.key).changedBy)
            .isEqualTo(AimiSettingsAuditChangedBy.SLIDER)
        assertThat(rows.getValue(DoubleKey.OApsAIMIPkpdInitialDiaH.key).changedBy)
            .isEqualTo(AimiSettingsAuditChangedBy.PRESET)
        assertThat(rows.getValue(BooleanKey.OApsAIMIMLtraining.key).changedBy)
            .isEqualTo(AimiSettingsAuditChangedBy.USER)
        assertThat(rows.getValue(DoubleKey.AimiTubeHypoFloorMgdl.key).changedBy)
            .isEqualTo(AimiSettingsAuditChangedBy.LOOP)
    }

    @Test
    fun `the reset restores the shipped default in every type branch`() {
        // One key per branch of `readDiff`. The first version of this test covered the Boolean
        // branch alone, so writing `0.0`, `""` or `0L` instead of the key's own default in the three
        // other branches would have gone through unnoticed — and these are dosing values.
        every { preferences.getIfExists(BooleanKey.OApsAIMIMLtraining) } returns true
        every { preferences.getIfExists(DoubleKey.OApsAIMIweight) } returns 83.0
        every { preferences.getIfExists(IntKey.OApsAIMILunchinterval) } returns 99
        every { preferences.getIfExists(StringKey.OApsAIMIWCycleTrackingMode) } returns "something"
        every { preferences.getIfExists(AimiLongKey.LastPrebolusTime) } returns 1_700_000_000_000L

        val rows = AimiSettingsAudit.build(preferences).rows.associateBy { it.keyString }
        listOf(
            BooleanKey.OApsAIMIMLtraining.key,
            DoubleKey.OApsAIMIweight.key,
            IntKey.OApsAIMILunchinterval.key,
            StringKey.OApsAIMIWCycleTrackingMode.key,
            AimiLongKey.LastPrebolusTime.key,
        ).forEach { rows.getValue(it).resetToDefault(preferences) }

        verify { preferences.put(BooleanKey.OApsAIMIMLtraining, BooleanKey.OApsAIMIMLtraining.defaultValue) }
        verify { preferences.put(DoubleKey.OApsAIMIweight, DoubleKey.OApsAIMIweight.defaultValue) }
        verify { preferences.put(IntKey.OApsAIMILunchinterval, IntKey.OApsAIMILunchinterval.defaultValue) }
        verify { preferences.put(StringKey.OApsAIMIWCycleTrackingMode, StringKey.OApsAIMIWCycleTrackingMode.defaultValue) }
        verify { preferences.put(AimiLongKey.LastPrebolusTime, AimiLongKey.LastPrebolusTime.defaultValue) }
    }

    @Test
    fun `no row carries a zero title, which would crash the screen`() {
        // `PreferenceKey.titleResId` is a non-null Int that every key enum defaults to 0, and most
        // AIMI keys never set it — including this one, the stale value the audit screen was built
        // to surface. `stringResource(0)` throws, so a 0 must never reach a row.
        every { preferences.getIfExists(DoubleKey.OApsAIMIIsfFusionMaxChangePerTick) } returns 0.4

        val report = AimiSettingsAudit.build(preferences)

        val row = report.rows.single { it.keyString == DoubleKey.OApsAIMIIsfFusionMaxChangePerTick.key }
        assertThat(row.titleResId).isNull()
        assertThat(report.rows.map { it.titleResId }).doesNotContain(0)
    }

    @Test
    fun `every setting in scope can be compared, so none is silently dropped`() {
        // The audit dispatches on the key's interface and returns null for a type it does not know.
        // A new key type would then vanish from the one screen built to make stuck values visible.
        // Rather than list the supported types here — a list that would drift from the code it
        // checks — every key is made to differ from its default and every one must produce a row.
        every { preferences.getIfExists(any<BooleanNonPreferenceKey>()) } answers { !firstArg<BooleanNonPreferenceKey>().defaultValue }
        every { preferences.getIfExists(any<DoublePreferenceKey>()) } answers { firstArg<DoublePreferenceKey>().defaultValue + 1.0 }
        every { preferences.getIfExists(any<IntNonPreferenceKey>()) } answers { firstArg<IntNonPreferenceKey>().defaultValue + 1 }
        every { preferences.getIfExists(any<StringNonPreferenceKey>()) } answers { firstArg<StringNonPreferenceKey>().defaultValue + "x" }
        every { preferences.getIfExists(any<LongNonPreferenceKey>()) } answers { firstArg<LongNonPreferenceKey>().defaultValue + 1L }

        val report = AimiSettingsAudit.build(preferences)

        assertThat(report.rows).hasSize(report.scopedCount)
    }

    @Test
    fun `two values that differ are never shown as the same text`() {
        // The shared formatter rounds to two decimals, so 0.0305 and 0.03 would both read "0.03"
        // while the row claims they differ. Full precision wins over tidiness here: the screen
        // exists to be believed.
        every { preferences.getIfExists(DoubleKey.OApsAIMIIsfFusionMaxChangePerTick) } returns 0.0305

        val row = AimiSettingsAudit.build(preferences).rows
            .single { it.keyString == DoubleKey.OApsAIMIIsfFusionMaxChangePerTick.key }

        assertThat(row.storedValue?.valueText).isNotEqualTo(row.defaultValue?.valueText)
    }

    @Test
    fun `a stored value the app does not read back is marked as not used`() {
        // Simple mode makes `get` return the shipped default for a `defaultedBySM` key and ignore
        // what is stored. The row must still appear — the value is there and will come back the day
        // simple mode is turned off — but it must not claim the loop is running on it.
        every { preferences.getIfExists(IntKey.OApsAIMILunchinterval) } returns 8
        every { preferences.get(IntKey.OApsAIMILunchinterval) } returns IntKey.OApsAIMILunchinterval.defaultValue
        every { preferences.getIfExists(BooleanKey.OApsAIMIMLtraining) } returns true
        every { preferences.get(BooleanKey.OApsAIMIMLtraining) } returns true

        val rows = AimiSettingsAudit.build(preferences).rows.associateBy { it.keyString }

        assertThat(rows.getValue(IntKey.OApsAIMILunchinterval.key).storedValueIgnored).isTrue()
        assertThat(rows.getValue(BooleanKey.OApsAIMIMLtraining.key).storedValueIgnored).isFalse()
    }

    @Test
    fun `a key only the loop writes is shown but offers no reset`() {
        // Resetting runtime state is at best pointless and at worst harmful: this stored time is the
        // half of the 20-minute SMB spacing that survives a restart.
        every { preferences.getIfExists(AimiLongKey.LastPrebolusTime) } returns 1_700_000_000_000L
        every { preferences.getIfExists(BooleanKey.OApsAIMIMLtraining) } returns true

        val rows = AimiSettingsAudit.build(preferences).rows.associateBy { it.keyString }

        assertThat(rows.getValue(AimiLongKey.LastPrebolusTime.key).runtimeState).isTrue()
        assertThat(rows.getValue(BooleanKey.OApsAIMIMLtraining.key).runtimeState).isFalse()
    }

    @Test
    fun `the count line matches the rows`() {
        every { preferences.getIfExists(BooleanKey.OApsAIMIMLtraining) } returns true
        every { preferences.getIfExists(DoubleKey.OApsAIMIPkpdInitialDiaH) } returns 8.0

        val report = AimiSettingsAudit.build(preferences)

        // `changedCount` is `rows.size` by definition, so asserting the two are equal can never
        // fail. Only the real number is worth asserting.
        assertThat(report.changedCount).isEqualTo(2)
        // Every manifest entry is in scope except the one BOOKKEEPING-only key (the setup wizard
        // flag), which needs no screen anywhere, including this one.
        assertThat(report.scopedCount).isEqualTo(AimiSettingsManifest.entries.size - 1)
    }
}
