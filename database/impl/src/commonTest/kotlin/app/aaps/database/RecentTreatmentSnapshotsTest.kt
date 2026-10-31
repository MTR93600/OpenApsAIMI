package app.aaps.database

import app.aaps.database.entities.Bolus
import app.aaps.database.entities.TemporaryBasal
import app.aaps.database.entities.embedments.InsulinConfiguration
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The rules of [RecentTreatmentSnapshots] with lists in place of the database. Runs on the JVM and on the iOS
 * simulator. `RecentTreatmentSnapshotsSqlTest` (jvmTest) compares the answers with the real SQL.
 */
class RecentTreatmentSnapshotsTest {

    private val now = 10L * DAY
    private var clock = now

    // Rows as the DAO gives them: temp basals oldest first, boluses newest id first. The loaders apply the
    // window like the SQL does.
    private var temporaryBasalRows = listOf<TemporaryBasal>()
    private var bolusRows = listOf<Bolus>()
    private var temporaryBasalLoads = 0
    private var bolusLoads = 0
    private var onTemporaryBasalLoad: () -> Unit = {}

    private val snapshots = RecentTreatmentSnapshots(
        now = { clock },
        loadTemporaryBasals = { from ->
            temporaryBasalLoads++
            val rows = temporaryBasalRows.filter { it.timestamp >= from }
            // After the rows were read: a write that commits now is not in this copy.
            onTemporaryBasalLoad()
            rows
        },
        loadBoluses = { from -> bolusLoads++; bolusRows.filter { it.timestamp >= from } },
    )

    private val sqlNotExpected: suspend () -> TemporaryBasal? = { error("the copy should answer") }

    @Test
    fun `an active temp basal in the copy is the answer without SQL`() = runTest {
        val tbr = temporaryBasal(id = 1, start = now - HOUR, duration = 2 * HOUR)
        temporaryBasalRows = listOf(tbr)

        assertSame(tbr, snapshots.temporaryBasalActiveAt(now, sqlNotExpected))
    }

    @Test
    fun `of two running temp basals the one that started last wins like the SQL`() = runTest {
        val older = temporaryBasal(id = 1, start = now - 3 * HOUR, duration = 5 * HOUR)
        val newer = temporaryBasal(id = 2, start = now - HOUR, duration = 2 * HOUR)
        temporaryBasalRows = listOf(older, newer)

        assertSame(newer, snapshots.temporaryBasalActiveAt(now, sqlNotExpected))
    }

    @Test
    fun `with no active temp basal in the copy the SQL is asked so an old long suspend is found`() = runTest {
        // A Medtrum suspend: 80 hours, started before the copy's window.
        val suspend = temporaryBasal(id = 1, start = now - 4 * DAY, duration = 80 * HOUR)
        temporaryBasalRows = listOf(temporaryBasal(id = 2, start = now - 2 * HOUR, duration = HOUR))
        var sqlCalls = 0

        val result = snapshots.temporaryBasalActiveAt(now) { sqlCalls++; suspend }

        assertSame(suspend, result)
        assertEquals(1, sqlCalls)
    }

    @Test
    fun `a time before the copy's window goes to SQL`() = runTest {
        temporaryBasalRows = listOf(temporaryBasal(id = 1, start = now - HOUR, duration = 2 * HOUR))
        val old = temporaryBasal(id = 9, start = now - 5 * DAY, duration = HOUR)

        assertSame(old, snapshots.temporaryBasalActiveAt(now - 5 * DAY + 1) { old })
    }

    @Test
    fun `temp basals starting in a range come from the copy oldest first with both ends included`() = runTest {
        val a = temporaryBasal(id = 1, start = now - 3 * HOUR, duration = HOUR)
        val b = temporaryBasal(id = 2, start = now - 2 * HOUR, duration = HOUR)
        val c = temporaryBasal(id = 3, start = now - HOUR, duration = HOUR)
        temporaryBasalRows = listOf(a, b, c)

        assertEquals(listOf(a, b), snapshots.temporaryBasalsStartingBetween(now - 3 * HOUR, now - 2 * HOUR) { error("no SQL") })
        assertEquals(listOf<TemporaryBasal>(), snapshots.temporaryBasalsStartingBetween(now - 5 * DAY, now) { emptyList() })
    }

    @Test
    fun `boluses from a time and in a range keep the SQL order with the newest id first`() = runTest {
        val first = bolus(id = 1, time = now - 3 * HOUR)
        val second = bolus(id = 2, time = now - 2 * HOUR)
        val third = bolus(id = 3, time = now - HOUR)
        bolusRows = listOf(third, second, first)

        assertEquals(listOf(third, second), snapshots.bolusesFrom(now - 2 * HOUR) { error("no SQL") })
        assertEquals(listOf(second, first), snapshots.bolusesBetween(now - 3 * HOUR, now - 2 * HOUR) { error("no SQL") })
        val older = listOf(bolus(id = 0, time = now - 5 * DAY))
        assertSame(older, snapshots.bolusesFrom(now - 5 * DAY) { older })
    }

    @Test
    fun `a written temp basal reloads only the temp basal copy`() = runTest {
        temporaryBasalRows = listOf(temporaryBasal(id = 1, start = now - HOUR, duration = 2 * HOUR))
        snapshots.temporaryBasalActiveAt(now, sqlNotExpected)
        snapshots.bolusesFrom(now - HOUR) { error("no SQL") }

        val newTbr = temporaryBasal(id = 2, start = now - 10 * MIN, duration = HOUR)
        temporaryBasalRows = temporaryBasalRows + newTbr
        snapshots.invalidateAfter(listOf(newTbr))

        assertSame(newTbr, snapshots.temporaryBasalActiveAt(now, sqlNotExpected))
        snapshots.bolusesFrom(now - HOUR) { error("no SQL") }
        assertEquals(2, temporaryBasalLoads)
        assertEquals(1, bolusLoads)
    }

    @Test
    fun `a written bolus reloads only the bolus copy`() = runTest {
        snapshots.temporaryBasalsStartingBetween(now - HOUR, now) { error("no SQL") }
        assertEquals(listOf<Bolus>(), snapshots.bolusesFrom(now - HOUR) { error("no SQL") })

        val smb = bolus(id = 1, time = now - MIN)
        bolusRows = listOf(smb)
        snapshots.invalidateAfter(listOf(smb))

        assertEquals(listOf(smb), snapshots.bolusesFrom(now - HOUR) { error("no SQL") })
        snapshots.temporaryBasalsStartingBetween(now - HOUR, now) { error("no SQL") }
        assertEquals(1, temporaryBasalLoads)
        assertEquals(2, bolusLoads)
    }

    @Test
    fun `a copy loaded while a write ran is not used after that write`() = runTest {
        val newTbr = temporaryBasal(id = 2, start = now - 10 * MIN, duration = HOUR)
        // The write commits and is announced while the first load is running.
        onTemporaryBasalLoad = {
            onTemporaryBasalLoad = {}
            snapshots.invalidateTemporaryBasals()
            temporaryBasalRows = listOf(newTbr)
        }

        snapshots.temporaryBasalsStartingBetween(now - HOUR, now) { error("no SQL") }

        assertSame(newTbr, snapshots.temporaryBasalActiveAt(now, sqlNotExpected))
        assertEquals(2, temporaryBasalLoads)
    }

    @Test
    fun `invalidateAll and the max age reload the copies`() = runTest {
        snapshots.bolusesFrom(now - HOUR) { error("no SQL") }
        snapshots.invalidateAll()
        snapshots.bolusesFrom(now - HOUR) { error("no SQL") }
        assertEquals(2, bolusLoads)

        clock = now + RecentTreatmentSnapshots.MAX_AGE_MS + 1
        snapshots.bolusesFrom(now - HOUR) { error("no SQL") }
        assertEquals(3, bolusLoads)
    }

    @Test
    fun `a temp basal that ended is not active`() = runTest {
        temporaryBasalRows = listOf(temporaryBasal(id = 1, start = now - 2 * HOUR, duration = HOUR))

        assertNull(snapshots.temporaryBasalActiveAt(now) { null })
    }

    private fun temporaryBasal(id: Long, start: Long, duration: Long) = TemporaryBasal(
        id = id,
        timestamp = start,
        utcOffset = 0,
        type = TemporaryBasal.Type.NORMAL,
        isAbsolute = true,
        rate = 1.0,
        duration = duration,
    )

    private fun bolus(id: Long, time: Long) = Bolus(
        id = id,
        timestamp = time,
        utcOffset = 0,
        amount = 0.5,
        type = Bolus.Type.SMB,
        insulinConfiguration = InsulinConfiguration("test", 5 * HOUR, 75 * MIN, 1.0),
    )

    private companion object {

        const val MIN = 60_000L
        const val HOUR = 60 * MIN
        const val DAY = 24 * HOUR
    }
}
