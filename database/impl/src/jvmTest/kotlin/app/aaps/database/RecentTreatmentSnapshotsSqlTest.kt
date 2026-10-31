package app.aaps.database

import app.aaps.database.di.JvmAppDatabaseBuilder
import app.aaps.database.entities.Bolus
import app.aaps.database.entities.TemporaryBasal
import app.aaps.database.entities.embedments.InsulinConfiguration
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * [RecentTreatmentSnapshots] gives the same answers as the SQL it replaces, on a real database file.
 *
 * The data is what AIMI writes: a temp basal every few minutes, often overlapping, plus invalid rows,
 * historic copies (referenceId set) and a Medtrum pump suspend of 80 hours that started before the copy's
 * window. iOS runs the same bundled SQLite and the same shared code.
 */
class RecentTreatmentSnapshotsSqlTest {

    private val directory = createTempDirectory("aaps-snapshot-test")
    private val database = JvmAppDatabaseBuilder().provideAppDatabase("$directory/aaps.db")
    private val now = NOW

    private val snapshots = RecentTreatmentSnapshots(
        now = { now },
        loadTemporaryBasals = { from -> database.temporaryBasalDao.getTemporaryBasalDataFromTime(from) },
        loadBoluses = { from -> database.bolusDao.getBolusesFromTime(from) },
    )

    @AfterTest
    fun tearDown() {
        database.close()
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `active temp basal is the same as the SQL at every time`() = runTest {
        fillTemporaryBasals()
        var answeredByCopy = 0

        var t = now - 5 * DAY
        while (t <= now + HOUR) {
            val sql = database.temporaryBasalDao.getTemporaryBasalActiveAt(t)
            var sqlAsked = false
            val fromCopy = snapshots.temporaryBasalActiveAt(t) { sqlAsked = true; database.temporaryBasalDao.getTemporaryBasalActiveAt(t) }
            assertThat(fromCopy?.id).isEqualTo(sql?.id)
            if (!sqlAsked) answeredByCopy++
            t += 7 * MIN
        }
        // The copy must really answer most of the recent reads, or it saves nothing.
        assertThat(answeredByCopy).isGreaterThan(300)
    }

    @Test
    fun `the Medtrum suspend is found through SQL while it runs`() = runTest {
        fillTemporaryBasals()
        // Inside the copy's window, during the suspend: AIMI set no temp basal then, so nothing in the copy is
        // active, and only the SQL can find the suspend that started before the window.
        val inSuspend = SUSPEND_START + 76 * HOUR

        val result = snapshots.temporaryBasalActiveAt(inSuspend) { database.temporaryBasalDao.getTemporaryBasalActiveAt(inSuspend) }

        assertThat(result?.duration).isEqualTo(80 * HOUR)
    }

    @Test
    fun `temp basals starting in a range are the same as the SQL`() = runTest {
        fillTemporaryBasals()
        for ((from, to) in ranges()) {
            val sql = database.temporaryBasalDao.getTemporaryBasalStartingFromTimeToTime(from, to)
            val fromCopy = snapshots.temporaryBasalsStartingBetween(from, to) { database.temporaryBasalDao.getTemporaryBasalStartingFromTimeToTime(from, to) }
            assertThat(fromCopy.map { it.id }).containsExactlyElementsIn(sql.map { it.id }).inOrder()
        }
    }

    @Test
    fun `boluses from a time and in a range are the same as the SQL`() = runTest {
        fillBoluses()
        for ((from, to) in ranges()) {
            val sqlFrom = database.bolusDao.getBolusesFromTime(from)
            assertThat(snapshots.bolusesFrom(from) { database.bolusDao.getBolusesFromTime(from) }.map { it.id })
                .containsExactlyElementsIn(sqlFrom.map { it.id }).inOrder()
            val sqlRange = database.bolusDao.getBolusesFromTime(from, to)
            assertThat(snapshots.bolusesBetween(from, to) { database.bolusDao.getBolusesFromTime(from, to) }.map { it.id })
                .containsExactlyElementsIn(sqlRange.map { it.id }).inOrder()
        }
    }

    private fun ranges(): List<Pair<Long, Long>> = listOf(
        now - 2 * HOUR to now,
        now - DAY to now - 12 * HOUR,
        now - 2 * DAY to now + HOUR,
        now - 10 * DAY to now, // before the copy's window: SQL
        now - 30 * MIN to now - 30 * MIN,
    )

    private suspend fun fillTemporaryBasals() {
        val random = Random(42)
        // A Medtrum suspend: 0 % for 80 hours. It starts before the copy's 3 day window and ends about 10 hours
        // inside it. No AIMI temp basal while it runs.
        val suspendStart = SUSPEND_START
        database.temporaryBasalDao.insert(temporaryBasal(suspendStart, 80 * HOUR, rate = 0.0))
        var t = now - 6 * DAY
        while (t < now) {
            val inSuspend = t >= suspendStart && t < suspendStart + 80 * HOUR
            if (!inSuspend) {
                val duration = (5 + random.nextInt(120)) * MIN
                val row = temporaryBasal(t, duration)
                when (random.nextInt(20)) {
                    0    -> row.isValid = false
                    1    -> row.referenceId = 1 // a historic copy
                    else -> Unit
                }
                database.temporaryBasalDao.insert(row)
            }
            t += (3 + random.nextInt(20)) * MIN
        }
    }

    private suspend fun fillBoluses() {
        val random = Random(7)
        var t = now - 6 * DAY
        while (t < now) {
            val row = bolus(t)
            when (random.nextInt(20)) {
                0    -> row.isValid = false
                1    -> row.referenceId = 1
                else -> Unit
            }
            database.bolusDao.insert(row)
            t += (3 + random.nextInt(30)) * MIN
        }
    }

    private fun temporaryBasal(start: Long, duration: Long, rate: Double = 1.0) = TemporaryBasal(
        timestamp = start,
        utcOffset = 0,
        type = TemporaryBasal.Type.NORMAL,
        isAbsolute = true,
        rate = rate,
        duration = duration,
    )

    private fun bolus(time: Long) = Bolus(
        timestamp = time,
        utcOffset = 0,
        amount = 0.3,
        type = Bolus.Type.SMB,
        insulinConfiguration = InsulinConfiguration("test", 5 * HOUR, 75 * MIN, 1.0),
    )

    private companion object {

        const val MIN = 60_000L
        const val HOUR = 60 * MIN
        const val DAY = 24 * HOUR
        const val NOW = 30L * DAY
        const val SUSPEND_START = NOW - 6 * DAY + HOUR
    }
}
