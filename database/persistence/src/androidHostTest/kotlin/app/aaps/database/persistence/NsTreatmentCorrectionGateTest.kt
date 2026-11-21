package app.aaps.database.persistence

import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.database.AppRepository
import app.aaps.database.transactions.SyncNsBolusTransaction
import app.aaps.database.transactions.SyncNsTemporaryBasalTransaction
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * The switch is read only on a follower. A master must not consult it.
 */
internal class NsTreatmentCorrectionGateTest {

    @Test
    fun `master does not read the switch`() = runTest {
        val preferences = mock<Preferences>()
        val flags = runBoth(client = false, preferences = preferences)

        assertFalse(flags.bolus)
        assertFalse(flags.basal)
        verify(preferences, never()).get(BooleanKey.NsClientAcceptTreatmentCorrections)
    }

    @Test
    fun `client with the switch off does not accept corrections`() = runTest {
        val preferences = mock<Preferences>()
        whenever(preferences.get(BooleanKey.NsClientAcceptTreatmentCorrections)).thenReturn(false)
        val flags = runBoth(client = true, preferences = preferences)

        assertFalse(flags.bolus)
        assertFalse(flags.basal)
        verify(preferences, times(2)).get(BooleanKey.NsClientAcceptTreatmentCorrections)
    }

    @Test
    fun `client with the switch on accepts corrections`() = runTest {
        val preferences = mock<Preferences>()
        whenever(preferences.get(BooleanKey.NsClientAcceptTreatmentCorrections)).thenReturn(true)
        val flags = runBoth(client = true, preferences = preferences)

        assertTrue(flags.bolus)
        assertTrue(flags.basal)
    }

    private suspend fun runBoth(client: Boolean, preferences: Preferences): Flags {
        val flags = Flags()
        val config = mock<Config>().also { whenever(it.AAPSCLIENT).thenReturn(client) }
        val repository = mock<AppRepository>()
        whenever(repository.runTransactionForResultSuspend<Any>(any())).thenAnswer { invocation ->
            when (val tx = invocation.arguments[0]) {
                is SyncNsBolusTransaction          -> {
                    flags.bolus = tx.acceptCorrections
                    SyncNsBolusTransaction.TransactionResult()
                }
                is SyncNsTemporaryBasalTransaction -> {
                    flags.basal = tx.acceptCorrections
                    SyncNsTemporaryBasalTransaction.TransactionResult()
                }
                else                               -> error("unexpected transaction")
            }
        }
        val sut = PersistenceLayerImpl(
            mock<AAPSLogger>(),
            repository,
            mock<DateUtil>(),
            config,
            { mock<APSResult>() },
            mock<FabricPrivacy>(),
            { preferences }
        )
        sut.syncNsBolus(emptyList(), doLog = false)
        sut.syncNsTemporaryBasals(emptyList(), doLog = false)
        return flags
    }

    private class Flags {
        var bolus: Boolean = false
        var basal: Boolean = false
    }
}
