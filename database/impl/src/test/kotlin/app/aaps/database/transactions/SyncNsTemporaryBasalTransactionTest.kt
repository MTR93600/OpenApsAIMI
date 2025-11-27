package app.aaps.database.transactions

import app.aaps.database.DelegatedAppDatabase
import app.aaps.database.daos.TemporaryBasalDao
import app.aaps.database.entities.TemporaryBasal
import app.aaps.database.entities.embedments.InterfaceIDs
import app.aaps.database.entities.interfaces.end
import com.google.common.truth.Truth.assertThat
import io.reactivex.rxjava3.core.Maybe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.verify
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class SyncNsTemporaryBasalTransactionTest {

    private lateinit var database: DelegatedAppDatabase
    private lateinit var temporaryBasalDao: TemporaryBasalDao

    @BeforeEach
    fun setup() {
        temporaryBasalDao = mock()
        database = mock()
        whenever(database.temporaryBasalDao).thenReturn(temporaryBasalDao)
    }

    @Test
    fun `inserts new when nsId not found and no active basal`() {
        val tb = createTemporaryBasal(id = 0, nsId = "ns-123", timestamp = 1000L, duration = 60_000L)

        whenever(temporaryBasalDao.findByNSId("ns-123")).thenReturn(null)
        whenever(temporaryBasalDao.getTemporaryBasalActiveAt(1000L)).thenReturn(Maybe.empty())

        val transaction = SyncNsTemporaryBasalTransaction(listOf(tb), nsClientMode = false)
        transaction.database = database
        val result = transaction.run()

        assertThat(result.inserted).hasSize(1)
        assertThat(result.updatedNsId).isEmpty()

        verify(temporaryBasalDao).insertNewEntry(tb)
    }

    @Test
    fun `updates nsId when active basal at same timestamp`() {
        val tb = createTemporaryBasal(id = 0, nsId = "ns-123", timestamp = 1000L, duration = 60_000L)
        val existing = createTemporaryBasal(id = 1, nsId = null, timestamp = 999L, duration = 60_000L)

        whenever(temporaryBasalDao.findByNSId("ns-123")).thenReturn(null)
        whenever(temporaryBasalDao.getTemporaryBasalActiveAt(1000L)).thenReturn(Maybe.just(existing))

        val transaction = SyncNsTemporaryBasalTransaction(listOf(tb), nsClientMode = false)
        transaction.database = database
        val result = transaction.run()

        assertThat(result.updatedNsId).hasSize(1)
        assertThat(existing.interfaceIDs.nightscoutId).isEqualTo("ns-123")

        verify(temporaryBasalDao).updateExistingEntry(existing)
    }

    @Test
    fun `ends running basal and inserts new when timestamps differ`() {
        val tb = createTemporaryBasal(id = 0, nsId = "ns-123", timestamp = 5000L, duration = 60_000L)
        val existing = createTemporaryBasal(id = 1, nsId = null, timestamp = 1000L, duration = 60_000L)

        whenever(temporaryBasalDao.findByNSId("ns-123")).thenReturn(null)
        whenever(temporaryBasalDao.getTemporaryBasalActiveAt(5000L)).thenReturn(Maybe.just(existing))

        val transaction = SyncNsTemporaryBasalTransaction(listOf(tb), nsClientMode = false)
        transaction.database = database
        val result = transaction.run()

        assertThat(result.ended).hasSize(1)
        assertThat(existing.end).isEqualTo(5000L)
        assertThat(result.inserted).hasSize(1)

        verify(temporaryBasalDao).updateExistingEntry(existing)
        verify(temporaryBasalDao).insertNewEntry(tb)
    }

    @Test
    fun `invalidates when valid becomes invalid`() {
        val tb = createTemporaryBasal(id = 0, nsId = "ns-123", duration = 60_000L, isValid = false)
        val existing = createTemporaryBasal(id = 1, nsId = "ns-123", duration = 60_000L, isValid = true)

        whenever(temporaryBasalDao.findByNSId("ns-123")).thenReturn(existing)

        val transaction = SyncNsTemporaryBasalTransaction(listOf(tb), nsClientMode = false)
        transaction.database = database
        val result = transaction.run()

        assertThat(result.invalidated).hasSize(1)
        assertThat(existing.isValid).isFalse()
    }

    @Test
    fun `updates duration in NS client mode`() {
        val tb = createTemporaryBasal(id = 0, nsId = "ns-123", duration = 120_000L)
        val existing = createTemporaryBasal(id = 1, nsId = "ns-123", duration = 60_000L)

        whenever(temporaryBasalDao.findByNSId("ns-123")).thenReturn(existing)

        val transaction = SyncNsTemporaryBasalTransaction(listOf(tb), nsClientMode = true)
        transaction.database = database
        val result = transaction.run()

        assertThat(result.updatedDuration).hasSize(1)
        assertThat(existing.duration).isEqualTo(120_000L)
    }

    @Test
    fun `finds by pump ids when nsId not found`() {
        val tb = createTemporaryBasal(
            id = 0,
            nsId = "ns-123",
            timestamp = 1000L,
            duration = 60_000L,
            pumpId = 12345L,
            pumpType = InterfaceIDs.PumpType.DANA_I,
            pumpSerial = "ABC123"
        )
        val existing = createTemporaryBasal(id = 1, nsId = null, duration = 60_000L)

        whenever(temporaryBasalDao.findByNSId("ns-123")).thenReturn(null)
        whenever(temporaryBasalDao.findByPumpIds(12345L, InterfaceIDs.PumpType.DANA_I, "ABC123")).thenReturn(existing)

        val transaction = SyncNsTemporaryBasalTransaction(listOf(tb), nsClientMode = false)
        transaction.database = database
        val result = transaction.run()

        assertThat(result.invalidated).isEmpty()
        assertThat(result.inserted).isEmpty()
    }

    private fun createTemporaryBasal(
        id: Long,
        nsId: String?,
        timestamp: Long = System.currentTimeMillis(),
        duration: Long,
        isValid: Boolean = true,
        pumpId: Long? = null,
        pumpType: InterfaceIDs.PumpType? = null,
        pumpSerial: String? = null
    ): TemporaryBasal = TemporaryBasal(
        timestamp = timestamp,
        rate = 1.5,
        duration = duration,
        type = TemporaryBasal.Type.NORMAL,
        isAbsolute = true,
        isValid = isValid,
        interfaceIDs_backing = InterfaceIDs(
            nightscoutId = nsId,
            pumpId = pumpId,
            pumpType = pumpType,
            pumpSerial = pumpSerial
        )
    ).also { it.id = id }
}
