package app.aaps.ui.compose.afrezzaDialog

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.ICfg
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.insulin.InsulinManager
import app.aaps.core.interfaces.insulin.InsulinType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.ui.R
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import app.aaps.core.ui.R as CoreUiR

/**
 * Pins the Afrezza cartridge to IU mapping.
 *
 * A cartridge is labelled in inhaled units, which are not IU. Only the stored amount is halved;
 * every text the user reads must still carry the cartridge number they inhaled.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class AfrezzaDoseMappingTest {

    @Mock private lateinit var insulinManager: InsulinManager
    @Mock private lateinit var persistenceLayer: PersistenceLayer
    @Mock private lateinit var uel: UserEntryLogger
    @Mock private lateinit var dateUtil: DateUtil
    @Mock private lateinit var rh: ResourceHelper
    @Mock private lateinit var aapsLogger: AAPSLogger

    private val afrezzaIcfg = ICfg(insulinLabel = "Afrezza", peak = 40, dia = 2.5, concentration = 1.0)

    private lateinit var sut: AfrezzaDialogViewModel

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        // UnconfinedTestDispatcher so the viewModelScope coroutine in confirmAndLog runs at once.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        whenever(insulinManager.insulins).thenReturn(arrayListOf(afrezzaIcfg))
        whenever(dateUtil.now()).thenReturn(1_000L)
        whenever(rh.gs(eq(CoreUiR.string.afrezza_inhaled_cartridge), any())).thenReturn("Afrezza inhaled (8U)")
        whenever(rh.gs(eq(R.string.afrezza_logged), any())).thenReturn("8U Afrezza logged")
        sut = AfrezzaDialogViewModel(insulinManager, persistenceLayer, uel, dateUtil, rh, aapsLogger)
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun logCartridge(units: Int) {
        sut.selectCartridge(units)
        sut.confirmAndLog()
    }

    @Test
    fun `a 4U cartridge is stored as 2 IU`() {
        logCartridge(4)

        val captor = argumentCaptor<BS>()
        verifyBlocking(persistenceLayer) { insertOrUpdateBolus(captor.capture(), eq(Action.BOLUS), eq(Sources.AfrezzaDialog), anyOrNull()) }
        assertThat(captor.firstValue.amount).isWithin(1e-9).of(2.0)
    }

    @Test
    fun `a 8U cartridge is stored as 4 IU`() {
        logCartridge(8)

        val captor = argumentCaptor<BS>()
        verifyBlocking(persistenceLayer) { insertOrUpdateBolus(captor.capture(), eq(Action.BOLUS), eq(Sources.AfrezzaDialog), anyOrNull()) }
        assertThat(captor.firstValue.amount).isWithin(1e-9).of(4.0)
    }

    @Test
    fun `a 12U cartridge is stored as 6 IU`() {
        logCartridge(12)

        val captor = argumentCaptor<BS>()
        verifyBlocking(persistenceLayer) { insertOrUpdateBolus(captor.capture(), eq(Action.BOLUS), eq(Sources.AfrezzaDialog), anyOrNull()) }
        assertThat(captor.firstValue.amount).isWithin(1e-9).of(6.0)
    }

    @Test
    fun `the user entry log gets the same halved amount`() {
        logCartridge(8)

        val captor = argumentCaptor<ValueWithUnit>()
        verify(uel).log(eq(Action.BOLUS), eq(Sources.AfrezzaDialog), anyOrNull(), captor.capture())
        assertThat(captor.firstValue).isEqualTo(ValueWithUnit.Insulin(4.0))
    }

    @Test
    fun `the note carries the cartridge size, not the halved amount`() {
        logCartridge(8)

        verify(rh).gs(CoreUiR.string.afrezza_inhaled_cartridge, 8)
        val captor = argumentCaptor<BS>()
        verifyBlocking(persistenceLayer) { insertOrUpdateBolus(captor.capture(), eq(Action.BOLUS), eq(Sources.AfrezzaDialog), anyOrNull()) }
        assertThat(captor.firstValue.notes).isEqualTo("Afrezza inhaled (8U)")
    }

    @Test
    fun `the confirmation dialog shows the cartridge size`() {
        sut.selectCartridge(8)

        // The dialog reads selectedCartridge directly, so it must stay the number the user picked.
        assertThat(sut.uiState.value.selectedCartridge).isEqualTo(8)
        assertThat(sut.uiState.value.showConfirmation).isTrue()
    }

    @Test
    fun `the snackbar shows the cartridge size`() {
        logCartridge(8)

        verify(rh).gs(R.string.afrezza_logged, 8)
    }

    @Test
    fun `the stored insulin config is the Afrezza one`() {
        logCartridge(8)

        val captor = argumentCaptor<BS>()
        verifyBlocking(persistenceLayer) { insertOrUpdateBolus(captor.capture(), eq(Action.BOLUS), eq(Sources.AfrezzaDialog), anyOrNull()) }
        assertThat(captor.firstValue.iCfg.insulinPeakTime).isEqualTo(InsulinType.OREF_INHALED_AFREZZA.insulinPeakTime)
    }
}
