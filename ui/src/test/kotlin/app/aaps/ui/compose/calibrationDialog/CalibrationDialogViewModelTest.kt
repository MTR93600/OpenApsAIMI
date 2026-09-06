package app.aaps.ui.compose.calibrationDialog

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.calibration.AddEntryResult
import app.aaps.core.interfaces.calibration.Calibration
import app.aaps.core.interfaces.calibration.CalibrationStatus
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.source.XDripSource
import app.aaps.core.interfaces.sync.XDripBroadcast
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.ui.R
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
internal class CalibrationDialogViewModelTest {

    @Mock private lateinit var profileUtil: ProfileUtil
    @Mock private lateinit var profileFunction: ProfileFunction
    @Mock private lateinit var xDripBroadcast: XDripBroadcast
    @Mock private lateinit var xDripSource: XDripSource
    @Mock private lateinit var uel: UserEntryLogger
    @Mock private lateinit var glucoseStatusProvider: GlucoseStatusProvider
    @Mock private lateinit var activePlugin: ActivePlugin
    @Mock private lateinit var activeCalibration: Calibration
    @Mock private lateinit var persistenceLayer: PersistenceLayer
    @Mock private lateinit var dateUtil: DateUtil
    @Mock private lateinit var rh: ResourceHelper

    private lateinit var sut: CalibrationDialogViewModel

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        // refreshPreconditions() is launched on viewModelScope -> deferred by StandardTestDispatcher.
        Dispatchers.setMain(StandardTestDispatcher())
        whenever(profileUtil.units).thenReturn(GlucoseUnit.MGDL)
        whenever(profileUtil.fromMgdlToUnits(any(), any())).thenReturn(0.0)
        whenever(activePlugin.activeCalibration).thenReturn(activeCalibration)
        sut = CalibrationDialogViewModel(
            profileUtil, profileFunction, xDripBroadcast, xDripSource, uel, glucoseStatusProvider,
            activePlugin, persistenceLayer, dateUtil, rh
        )
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `no action when bg is zero`() {
        assertThat(sut.uiState.value.bg).isEqualTo(0.0)
        assertThat(sut.hasAction()).isFalse()
    }

    @Test
    fun `updateBg sets the value and enables the action`() {
        sut.updateBg(120.0)

        assertThat(sut.uiState.value.bg).isEqualTo(120.0)
        assertThat(sut.hasAction()).isTrue()
    }

    @Test
    fun `confirmAndSave on an accepted first entry tells the user it did not apply yet`() = runTest {
        whenever(activeCalibration.addEntry(any(), any())).thenReturn(AddEntryResult.Accepted)
        whenever(activeCalibration.status()).thenReturn(CalibrationStatus.NeedMoreEntries(1))
        whenever(rh.gs(eq(R.string.cal_saved_need_more_entries), any())).thenReturn("one more entry needed")

        sut.updateBg(120.0)
        sut.buildConfirmationSummary()
        sut.confirmAndSave()
        advanceUntilIdle()

        val effect = sut.sideEffect.replayCache.last() as CalibrationDialogViewModel.SideEffect.EntryAccepted
        assertThat(effect.message).isEqualTo("one more entry needed")
    }

    @Test
    fun `confirmAndSave on an accepted entry that already applies has no message`() = runTest {
        whenever(activeCalibration.addEntry(any(), any())).thenReturn(AddEntryResult.Accepted)
        whenever(activeCalibration.status()).thenReturn(CalibrationStatus.Applied)

        sut.updateBg(120.0)
        sut.buildConfirmationSummary()
        sut.confirmAndSave()
        advanceUntilIdle()

        val effect = sut.sideEffect.replayCache.last() as CalibrationDialogViewModel.SideEffect.EntryAccepted
        assertThat(effect.message).isNull()
    }
}
