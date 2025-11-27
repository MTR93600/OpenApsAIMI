package app.aaps.database.transactions

import app.aaps.database.DelegatedAppDatabase
import app.aaps.database.daos.ExtendedBolusDao
import app.aaps.database.entities.ExtendedBolus
import app.aaps.database.entities.embedments.InterfaceIDs
import app.aaps.database.entities.interfaces.end
import com.google.common.truth.Truth.assertThat
import io.reactivex.rxjava3.core.Maybe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.verify
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class SyncNsExtendedBolusTransactionTest {

    private lateinit var database: DelegatedAppDatabase
    private lateinit var extendedBolusDao: ExtendedBolusDao

    @BeforeEach
    fun setup() {
        extendedBolusDao = mock()
        database = mock()
        whenever(database.extendedBolusDao).thenReturn(extendedBolusDao)
    }

    @Test
    fun `inserts new when nsId not found and no active bolus`() {
        val eb = createExtendedBolus(id = 0, nsId = "ns-123", timestamp = 1000L, duration = 60_000L, amount = 1.0)

        whenever(extendedBolusDao.findByNSId("ns-123")).thenReturn(null)
        whenever(extendedBolusDao.getExtendedBolusActiveAt(1000L)).thenReturn(Maybe.empty())

        val transaction = SyncNsExtendedBolusTransaction(listOf(eb), nsClientMode = false)
        transaction.database = database
        val result = transaction.run()

        assertThat(result.inserted).hasSize(1)
        assertThat(result.updatedNsId).isEmpty()

        verify(extendedBolusDao).insertNewEntry(eb)
    }

    @Test
    fun `updates nsId when active bolus at same timestamp`() {
        val eb = createExtendedBolus(id = 0, nsId = "ns-123", timestamp = 1000L, duration = 60_000L, amount = 5.0)
        val existing = createExtendedBolus(id = 1, nsId = null, timestamp = 999L, duration = 60_000L, amount = 5.0)

        whenever(extendedBolusDao.findByNSId("ns-123")).thenReturn(null)
        whenever(extendedBolusDao.getExtendedBolusActiveAt(1000L)).thenReturn(Maybe.just(existing))

        val transaction = SyncNsExtendedBolusTransaction(listOf(eb), nsClientMode = false)
        transaction.database = database
        val result = transaction.run()

        assertThat(result.updatedNsId).hasSize(1)
        assertThat(existing.interfaceIDs.nightscoutId).isEqualTo("ns-123")

        verify(extendedBolusDao).updateExistingEntry(existing)
    }

    @Test
    fun `ends running bolus with proportional amount and inserts new`() {
        val eb = createExtendedBolus(id = 0, nsId = "ns-123", timestamp = 31_000L, duration = 60_000L, amount = 5.0)
        val existing = createExtendedBolus(id = 1, nsId = null, timestamp = 1000L, duration = 60_000L, amount = 6.0)

        whenever(extendedBolusDao.findByNSId("ns-123")).thenReturn(null)
        whenever(extendedBolusDao.getExtendedBolusActiveAt(31_000L)).thenReturn(Maybe.just(existing))

        val transaction = SyncNsExtendedBolusTransaction(listOf(eb), nsClientMode = false)
        transaction.database = database
        val result = transaction.run()

        assertThat(result.ended).hasSize(1)
        assertThat(existing.end).isEqualTo(31_000L)
        // Amount should be proportionally reduced: (31000-1000)/60000 = 0.5, so 6.0 * 0.5 = 3.0
        assertThat(existing.amount).isWithin(0.1).of(3.0)
        assertThat(result.inserted).hasSize(1)

        verify(extendedBolusDao).updateExistingEntry(existing)
        verify(extendedBolusDao).insertNewEntry(eb)
    }

    @Test
    fun `invalidates when valid becomes invalid`() {
        val eb = createExtendedBolus(id = 0, nsId = "ns-123", duration = 60_000L, amount = 5.0, isValid = false)
        val existing = createExtendedBolus(id = 1, nsId = "ns-123", duration = 60_000L, amount = 5.0, isValid = true)

        whenever(extendedBolusDao.findByNSId("ns-123")).thenReturn(existing)

        val transaction = SyncNsExtendedBolusTransaction(listOf(eb), nsClientMode = false)
        transaction.database = database
        val result = transaction.run()

        assertThat(result.invalidated).hasSize(1)
        assertThat(existing.isValid).isFalse()
    }

    @Test
    fun `updates duration and amount in NS client mode`() {
        val eb = createExtendedBolus(id = 0, nsId = "ns-123", duration = 120_000L, amount = 10.0)
        val existing = createExtendedBolus(id = 1, nsId = "ns-123", duration = 60_000L, amount = 5.0)

        whenever(extendedBolusDao.findByNSId("ns-123")).thenReturn(existing)

        val transaction = SyncNsExtendedBolusTransaction(listOf(eb), nsClientMode = true)
        transaction.database = database
        val result = transaction.run()

        assertThat(result.updatedDuration).hasSize(1)
        assertThat(existing.duration).isEqualTo(120_000L)
        assertThat(existing.amount).isEqualTo(10.0)
    }

    private fun createExtendedBolus(
        id: Long,
        nsId: String?,
        timestamp: Long = System.currentTimeMillis(),
        duration: Long,
        amount: Double,
        isValid: Boolean = true
    ): ExtendedBolus = ExtendedBolus(
        timestamp = timestamp,
        amount = amount,
        duration = duration,
        isEmulatingTempBasal = false,
        isValid = isValid,
        interfaceIDs_backing = InterfaceIDs(nightscoutId = nsId)
    ).also { it.id = id }
}
