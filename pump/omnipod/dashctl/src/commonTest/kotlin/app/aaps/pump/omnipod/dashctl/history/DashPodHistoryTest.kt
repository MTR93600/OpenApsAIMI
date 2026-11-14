package app.aaps.pump.omnipod.dashctl.history

import app.aaps.pump.omnipod.dashctl.session.DashActiveCommand
import app.aaps.pump.omnipod.dashctl.session.DashCommandConfirmation
import app.aaps.pump.omnipod.dashctl.session.DashCommandKind
import app.aaps.pump.omnipod.dashctl.session.DashLastBolus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/** [DashPodHistory] and [DashPodHistoryCodec]: the records Android `DashHistory` keeps, at the same points. */
class DashPodHistoryTest {

    private class MemoryStore : DashPodHistoryStore {
        var bytes: ByteArray? = null
        override fun load(): ByteArray? = bytes
        override fun save(bytes: ByteArray) {
            this.bytes = bytes
        }
    }

    private var now = NOW
    private val store = MemoryStore()
    private val history = DashPodHistory(store) { now }

    private fun command(kind: DashCommandKind, at: Long = NOW, units: Double? = null, rate: Double? = null, minutes: Int? = null, basalCorrection: Boolean = false) =
        DashActiveCommand(
            sequence = 1, kind = kind, historyId = 0L, statusCountAtCreation = 0L, sent = true,
            requestedBolusUnits = units, tempBasalRate = rate, tempBasalMinutes = minutes, createdAtMs = at, basalCorrection = basalCorrection
        )

    /**
     * The queue thread and the wizard write at the same time. Each write reads the list, changes it
     * and writes it back: without a lock one of two writes at once is lost.
     */
    @Test
    fun `two threads writing at once lose no record`() = runTest {
        val perThread = 250
        withContext(Dispatchers.Default) {
            (0 until 4).map { thread ->
                async {
                    repeat(perThread) { i ->
                        history.onCommandSent(command(DashCommandKind.BOLUS, at = NOW + thread * 10_000L + i, units = 0.05))
                    }
                }
            }.awaitAll()
        }

        assertEquals(4 * perThread, history.recentRecords().size)
        assertEquals(4 * perThread, DashPodHistoryCodec.decode(store.bytes!!).size, "the saved list has every record too")
    }

    @Test
    fun `a command is written when sent and decided by the pod`() {
        val bolus = command(DashCommandKind.BOLUS, units = 1.5)
        history.onCommandSent(bolus)
        assertFalse(history.recentRecords().single().isSuccess(), "sent, not decided yet")

        history.onCommandDecided(bolus, DashCommandConfirmation.SUCCESS, NOW + 1000)

        val record = history.recentRecords().single()
        assertTrue(record.isSuccess())
        assertEquals(DashHistoryType.SET_BOLUS, record.type)
        assertEquals(1.5, record.bolusUnits)
        assertEquals(DashHistoryBolusType.DEFAULT, record.bolusType)
        assertEquals(NOW + 1000, record.resolvedAtMs)
    }

    @Test
    fun `a refused command and a command not sent are failures`() {
        val tbr = command(DashCommandKind.TEMP_BASAL, at = NOW, rate = 1.0, minutes = 30)
        history.onCommandSent(tbr)
        history.onCommandDecided(tbr, DashCommandConfirmation.DENIED, NOW)
        val cancel = command(DashCommandKind.STOP_TEMP_BASAL, at = NOW + 1)
        history.onCommandDecided(cancel, DashCommandConfirmation.SENDING_FAILURE, NOW + 1)

        val records = history.recentRecords()
        assertEquals(DashHistoryResolvedResult.FAILURE, records.single { it.type == DashHistoryType.SET_TEMPORARY_BASAL }.resolvedResult)
        assertEquals(DashHistoryInitialResult.FAILURE_SENDING, records.single { it.type == DashHistoryType.CANCEL_TEMPORARY_BASAL }.initialResult)
        assertTrue(records.none { it.isSuccess() })
    }

    @Test
    fun `an SMB and a basal correction keep their kind`() {
        history.nextBolusIsSmb = true
        history.onCommandSent(command(DashCommandKind.BOLUS, at = NOW, units = 0.3))
        history.nextBolusIsSmb = false
        history.onCommandSent(command(DashCommandKind.BOLUS, at = NOW + 1, units = 0.05, basalCorrection = true))

        assertEquals(
            listOf(DashHistoryBolusType.BASAL_CORRECTION, DashHistoryBolusType.SMB),
            history.recentRecords().map { it.bolusType },
            "newest first"
        )
    }

    @Test
    fun `a bolus ended early keeps what went in`() {
        val bolus = command(DashCommandKind.BOLUS, units = 2.0)
        history.onCommandSent(bolus)
        history.onBolusEnded(DashLastBolus(startTimeMs = NOW, requestedUnits = 2.0, bolusUnitsRemaining = 1.5, deliveryComplete = true, createdAtMs = NOW))

        assertEquals(0.5, history.recentRecords().single().totalAmountDelivered)
    }

    @Test
    fun `status reads are not kept`() {
        history.onCommandSent(command(DashCommandKind.OTHER))
        assertTrue(history.recentRecords().isEmpty())
    }

    @Test
    fun `activation parts and the discard are written decided`() {
        history.addActivationStep(DashHistoryType.INITIALIZE_POD, success = true)
        history.addActivationStep(DashHistoryType.INSERT_CANNULA, success = false)

        val records = history.recentRecords()
        assertEquals(2, records.size, "two records in the same millisecond keep both")
        assertTrue(records.single { it.type == DashHistoryType.INITIALIZE_POD }.isSuccess())
        assertFalse(records.single { it.type == DashHistoryType.INSERT_CANNULA }.isSuccess())
    }

    @Test
    fun `the history survives a restart and drops what is older than 5 days`() {
        history.onCommandSent(command(DashCommandKind.BOLUS, at = NOW, units = 1.0))
        now = NOW + DashPodHistory.KEEP_MS + 1
        history.onCommandSent(command(DashCommandKind.TEMP_BASAL, at = now, rate = 0.5, minutes = 60))

        val restarted = DashPodHistory(store) { now }

        assertEquals(listOf(DashHistoryType.SET_TEMPORARY_BASAL), restarted.recentRecords().map { it.type })
    }

    @Test
    fun `the codec roundtrips every field`() {
        val records = listOf(
            DashHistoryRecord(NOW, DashHistoryType.SET_BOLUS, DashHistoryInitialResult.SENT, 1.0, DashHistoryBolusType.SMB, null, null, 0.5, DashHistoryResolvedResult.SUCCESS, NOW + 1),
            DashHistoryRecord(NOW + 2, DashHistoryType.SET_TEMPORARY_BASAL, DashHistoryInitialResult.FAILURE_SENDING, tempBasalRate = 1.25, tempBasalMinutes = 30),
            DashHistoryRecord(NOW + 3, DashHistoryType.DISCARD_POD, DashHistoryInitialResult.NOT_SENT),
        )
        assertEquals(records, DashPodHistoryCodec.decode(DashPodHistoryCodec.encode(records)))
    }

    @Test
    fun `a broken store gives an empty history`() {
        store.bytes = byteArrayOf(9, 9, 9)
        assertTrue(DashPodHistory(store) { now }.recentRecords().isEmpty())
        assertNull(DashHistoryType.of(DashCommandKind.OTHER))
    }

    private companion object {

        const val NOW = 1_767_225_600_000L
    }
}
