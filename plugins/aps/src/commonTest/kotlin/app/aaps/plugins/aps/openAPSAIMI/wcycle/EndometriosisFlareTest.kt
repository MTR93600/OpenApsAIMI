package app.aaps.plugins.aps.openAPSAIMI.wcycle

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.plugins.aps.openAPSAIMI.MapPreferences
import app.aaps.plugins.aps.openAPSAIMI.NoOpAapsLogger
import app.aaps.plugins.aps.openAPSAIMI.keys.AimiLongKey
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Step S3b: a declared endometriosis pain flare ends by itself after `AimiEndometriosisFlareDuration`
 * hours (default 4). Before, nothing read the duration and a flare that was not switched off raised
 * the basal and damped the SMBs for ever.
 */
class EndometriosisFlareTest {

    private val hourMs = 60 * 60 * 1000L
    private val now = 1_000 * hourMs

    private fun preferences(flareOn: Boolean, startedAt: Long? = null, durationHours: Int? = null) = MapPreferences().apply {
        booleans[BooleanKey.AimiEndometriosisEnable] = true
        booleans[BooleanKey.AimiEndometriosisPainFlare] = flareOn
        startedAt?.let { longs[AimiLongKey.EndometriosisFlareStartedAt] = it }
        durationHours?.let { ints[IntKey.AimiEndometriosisFlareDuration] = it }
    }

    private fun factors(preferences: MapPreferences, nowMs: Long = now) =
        EndometriosisAdjuster(preferences, NoOpAapsLogger).calculateFactors(bg = 160.0, delta = 2.0, nowMs = nowMs)

    // ---------------------------------------------------------------- the adjuster

    @Test
    fun `a flare inside its duration raises the basal and damps the SMB`() {
        val result = factors(preferences(flareOn = true, startedAt = now - 1 * hourMs))
        assertEquals(1.3, result.basalMult)
        assertEquals(0.7, result.smbMult)
        assertTrue("PainFlare" in result.reason)
    }

    @Test
    fun `a flare ends by itself after the default four hours`() {
        val result = factors(preferences(flareOn = true, startedAt = now - 4 * hourMs - 1))
        assertEquals(1.0, result.basalMult)
        assertEquals(1.0, result.smbMult)
        assertEquals(1.0, result.isfMult)
        assertTrue("PainFlare(Ended)" in result.reason)
    }

    @Test
    fun `the duration setting is read`() {
        val prefs = preferences(flareOn = true, startedAt = now - 5 * hourMs, durationHours = 6)
        assertEquals(1.3, factors(prefs).basalMult)
        assertEquals(1.0, factors(prefs, nowMs = now + 1 * hourMs + 1).basalMult)
    }

    @Test
    fun `a flare with no start time starts now`() {
        // A flare switched on before this build, or by an import: it gets a full duration from now.
        val prefs = preferences(flareOn = true)
        assertEquals(1.3, factors(prefs).basalMult)
        assertEquals(now, prefs.longs[AimiLongKey.EndometriosisFlareStartedAt])
    }

    @Test
    fun `switching the flare off clears its start time`() {
        val prefs = preferences(flareOn = false, startedAt = now - hourMs)
        factors(prefs)
        assertEquals(0L, prefs.longs[AimiLongKey.EndometriosisFlareStartedAt])
    }

    // ---------------------------------------------------------------- the clock

    @Test
    fun `the start time is kept or set or cleared`() {
        assertEquals(0L, EndometriosisFlareClock.startToStore(flareOn = false, storedStartMs = 5L, nowMs = now))
        assertEquals(now, EndometriosisFlareClock.startToStore(flareOn = true, storedStartMs = 0L, nowMs = now))
        assertEquals(5L, EndometriosisFlareClock.startToStore(flareOn = true, storedStartMs = 5L, nowMs = now))
    }

    @Test
    fun `the status gives the time left and then ended`() {
        assertEquals(EndometriosisFlareClock.Status.Off, EndometriosisFlareClock.status(false, now, 4, now))
        assertEquals(
            EndometriosisFlareClock.Status.Active(remainingMinutes = 150),
            EndometriosisFlareClock.status(true, now - 90 * 60_000L, 4, now),
        )
        assertEquals(EndometriosisFlareClock.Status.Ended, EndometriosisFlareClock.status(true, now - 4 * hourMs, 4, now))
        // Not started yet (the next loop run stores the start): the whole duration is left.
        assertEquals(EndometriosisFlareClock.Status.Active(remainingMinutes = 240), EndometriosisFlareClock.status(true, 0L, 4, now))
        // A start in the future (the clock went back) never gives more than the whole duration.
        assertEquals(EndometriosisFlareClock.Status.Active(remainingMinutes = 240), EndometriosisFlareClock.status(true, now + hourMs, 4, now))
    }

    // ---------------------------------------------------------------- the switch

    @Test
    fun `the switch clears the start when it is turned off also with endometriosis off`() = runTest {
        val prefs = preferences(flareOn = true, startedAt = now - hourMs).apply {
            booleans[BooleanKey.AimiEndometriosisEnable] = false
        }
        EndometriosisFlareSwitchWatcher.storeStarts(flowOf(true, false), prefs, nowMs = { now }).toList()
        assertEquals(0L, prefs.longs[AimiLongKey.EndometriosisFlareStartedAt])
    }

    @Test
    fun `switching the flare off and on again with no loop run starts a new flare`() = runTest {
        // An old flare started 3 h ago. The person taps the switch off, then on, before the next loop
        // run. The new flare must get its whole duration, not the hour left of the old one.
        val prefs = preferences(flareOn = true, startedAt = now - 3 * hourMs)
        val stored = EndometriosisFlareSwitchWatcher.storeStarts(flowOf(true, false, true), prefs, nowMs = { now }).toList()
        assertEquals(listOf(now - 3 * hourMs, 0L, now), stored)
        assertEquals(now, prefs.longs[AimiLongKey.EndometriosisFlareStartedAt])
        assertEquals(
            EndometriosisFlareClock.Status.Active(remainingMinutes = 240),
            EndometriosisFlareClock.status(true, prefs.longs.getValue(AimiLongKey.EndometriosisFlareStartedAt), 4, now),
        )
        assertEquals(1.3, factors(prefs).basalMult)
    }

    @Test
    fun `the first value of the switch keeps a running flare`() = runTest {
        // The app starts while a flare is on: it must not start again from zero.
        val prefs = preferences(flareOn = true, startedAt = now - 3 * hourMs)
        EndometriosisFlareSwitchWatcher.storeStarts(flowOf(true), prefs, nowMs = { now }).toList()
        assertEquals(now - 3 * hourMs, prefs.longs[AimiLongKey.EndometriosisFlareStartedAt])
    }

    @Test
    fun `the switch sets the start even when endometriosis is off`() = runTest {
        val prefs = preferences(flareOn = false).apply { booleans[BooleanKey.AimiEndometriosisEnable] = false }
        EndometriosisFlareSwitchWatcher.storeStarts(flowOf(false, true), prefs, nowMs = { now }).toList()
        assertEquals(now, prefs.longs[AimiLongKey.EndometriosisFlareStartedAt])
    }
}
