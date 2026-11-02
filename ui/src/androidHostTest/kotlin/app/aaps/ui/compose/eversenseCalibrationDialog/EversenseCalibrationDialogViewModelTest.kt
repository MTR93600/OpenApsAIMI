package app.aaps.ui.compose.eversenseCalibrationDialog

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.source.EversenseCalibrationSource
import app.aaps.core.keys.interfaces.TextRef
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
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
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
internal class EversenseCalibrationDialogViewModelTest {

    @Mock private lateinit var profileUtil: ProfileUtil
    @Mock private lateinit var eversenseCalibrationSource: EversenseCalibrationSource
    @Mock private lateinit var uel: UserEntryLogger
    @Mock private lateinit var rh: TextResolver

    private lateinit var testDispatcher: TestDispatcher

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        whenever(rh.gs(any<TextRef>())).thenReturn("send failed")
        whenever(eversenseCalibrationSource.readinessMessage()).thenReturn("")
        whenever(eversenseCalibrationSource.isConnected()).thenReturn(true)
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun mgdlProfile() {
        whenever(profileUtil.units).thenReturn(GlucoseUnit.MGDL)
        whenever(profileUtil.fromMgdlToUnits(40.0)).thenReturn(40.0)
        whenever(profileUtil.fromMgdlToUnits(400.0)).thenReturn(400.0)
    }

    private fun mmolProfile() {
        whenever(profileUtil.units).thenReturn(GlucoseUnit.MMOL)
        whenever(profileUtil.fromMgdlToUnits(40.0)).thenReturn(2.2196)
        whenever(profileUtil.fromMgdlToUnits(400.0)).thenReturn(22.1959)
    }

    private fun newViewModel() = EversenseCalibrationDialogViewModel(profileUtil, eversenseCalibrationSource, uel, rh)

    @Test
    fun `mg dl profile offers the 40 to 400 range`() {
        mgdlProfile()

        val state = newViewModel().uiState.value

        assertThat(state.bgRange).isEqualTo(40.0..400.0)
        assertThat(state.bgStep).isEqualTo(1.0)
        assertThat(state.bgDecimalPlaces).isEqualTo(0)
    }

    @Test
    fun `mmol profile offers the 2 point 2 to 22 point 2 range`() {
        mmolProfile()

        val state = newViewModel().uiState.value

        assertThat(state.bgRange.start).isWithin(0.001).of(2.2196)
        assertThat(state.bgRange.endInclusive).isWithin(0.001).of(22.1959)
        assertThat(state.bgStep).isEqualTo(0.1)
        assertThat(state.bgDecimalPlaces).isEqualTo(1)
    }

    // The whole reason the code rounds instead of truncating: 2.2 mmol/L is the low bound we show
    // the user, and 39 mg/dL would be refused by the transmitter path.
    @Test
    fun `submit rounds a mmol value up to the lowest accepted mg dl value`() = runTest(testDispatcher) {
        mmolProfile()
        whenever(profileUtil.convertToMgdl(2.2, GlucoseUnit.MMOL)).thenReturn(39.634)
        whenever(eversenseCalibrationSource.calibrate(40)).thenReturn(true)

        val sut = newViewModel()
        sut.updateBg(2.2)
        sut.submit()
        advanceUntilIdle()

        verify(eversenseCalibrationSource).calibrate(40)
        verify(eversenseCalibrationSource, never()).calibrate(39)
    }

    @Test
    fun `submit is blocked while the transmitter is not ready`() = runTest(testDispatcher) {
        mgdlProfile()
        whenever(eversenseCalibrationSource.readinessMessage()).thenReturn("not ready")

        val sut = newViewModel()
        sut.updateBg(120.0)

        assertThat(sut.uiState.value.canSubmit).isFalse()

        sut.submit()
        advanceUntilIdle()

        verify(eversenseCalibrationSource, never()).calibrate(any())
    }

    @Test
    fun `submit is blocked while the input field shows bad text`() = runTest(testDispatcher) {
        mgdlProfile()

        val sut = newViewModel()
        // The user typed a good value, then kept typing until the text was not a number. The field
        // does not publish such text, so bg still holds 120 - only the error flag moves.
        sut.updateBg(120.0)
        sut.updateBgInputError(true)

        assertThat(sut.uiState.value.canSubmit).isFalse()

        sut.submit()
        advanceUntilIdle()

        // Without the flag this would have sent the older 120 while the field showed something else.
        verify(eversenseCalibrationSource, never()).calibrate(any())

        // Back to good text: the button works again.
        sut.updateBgInputError(false)
        assertThat(sut.uiState.value.canSubmit).isTrue()
    }

    @Test
    fun `a failed calibration emits the failure side effect and clears submitting`() = runTest(testDispatcher) {
        mgdlProfile()
        whenever(profileUtil.convertToMgdl(120.0, GlucoseUnit.MGDL)).thenReturn(120.0)
        whenever(eversenseCalibrationSource.calibrate(120)).thenReturn(false)

        val sut = newViewModel()
        sut.updateBg(120.0)
        sut.submit()
        advanceUntilIdle()

        val effect = sut.sideEffect.first()
        assertThat(effect).isInstanceOf(EversenseCalibrationDialogViewModel.SideEffect.CalibrationFailed::class.java)
        assertThat(sut.uiState.value.submitting).isFalse()
    }

    @Test
    fun `a successful calibration emits accepted and logs a user entry`() = runTest(testDispatcher) {
        mgdlProfile()
        whenever(profileUtil.convertToMgdl(120.0, GlucoseUnit.MGDL)).thenReturn(120.0)
        whenever(eversenseCalibrationSource.calibrate(120)).thenReturn(true)

        val sut = newViewModel()
        sut.updateBg(120.0)
        sut.submit()
        advanceUntilIdle()

        val effect = sut.sideEffect.first()
        assertThat(effect).isEqualTo(EversenseCalibrationDialogViewModel.SideEffect.CalibrationAccepted)
        verify(uel).log(action = any(), source = any(), note = eq(""), value = any())
    }
}
