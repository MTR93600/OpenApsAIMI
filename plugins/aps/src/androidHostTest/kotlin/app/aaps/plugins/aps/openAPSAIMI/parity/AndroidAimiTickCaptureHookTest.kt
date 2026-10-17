package app.aaps.plugins.aps.openAPSAIMI.parity

import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * The recorder switch must follow the expert preference, in both directions, at every tick.
 * Before this, nothing wrote the switch and the recorder never ran.
 */
class AndroidAimiTickCaptureHookTest {

    private val preferences = mock<Preferences>()
    private val hook = AndroidAimiTickCaptureHook(preferences)
    private var savedMaxTicks = 0

    @BeforeEach
    fun setUp() {
        savedMaxTicks = AimiParityCaptureHook.maxTicks
        // Zero ticks allowed: a tick that reaches the recorder returns before it builds anything.
        AimiParityCaptureHook.maxTicks = 0
        AimiParityCaptureHook.enabled = false
    }

    @AfterEach
    fun tearDown() {
        AimiParityCaptureHook.enabled = false
        AimiParityCaptureHook.maxTicks = savedMaxTicks
    }

    private fun tick() = hook.maybeCapture(
        storage = mock<AimiStorage>(),
        glucoseStatusAimi = mock<GlucoseStatusAIMI>(),
        currentTemp = mock<CurrentTemp>(),
        iobArray = emptyArray(),
        profile = mock<OapsProfileAimi>(),
        autosensResult = mock<AutosensResult>(),
        mealData = mock<MealData>(),
        microBolusAllowed = true,
        currentTime = 0L,
        flatBGsDetected = false,
        dynIsfMode = false,
        pkpdIobDataArray = null,
        effectiveDiaHours = null,
        effectivePeakMinutes = null,
        rt = mock<RT>(),
    )

    @Test
    fun `preference off keeps the recorder off after a tick`() {
        whenever(preferences.get(BooleanKey.AimiParityCaptureEnable)).thenReturn(false)
        tick()
        assertFalse(AimiParityCaptureHook.enabled)
    }

    @Test
    fun `turning the preference on makes the recorder switch true at the next tick`() {
        whenever(preferences.get(BooleanKey.AimiParityCaptureEnable)).thenReturn(true)
        assertFalse(AimiParityCaptureHook.enabled)
        tick()
        assertTrue(AimiParityCaptureHook.enabled)
    }

    @Test
    fun `turning the preference off again switches the recorder off while the app runs`() {
        whenever(preferences.get(BooleanKey.AimiParityCaptureEnable)).thenReturn(true)
        tick()
        assertTrue(AimiParityCaptureHook.enabled)

        whenever(preferences.get(BooleanKey.AimiParityCaptureEnable)).thenReturn(false)
        tick()
        assertFalse(AimiParityCaptureHook.enabled)
    }
}
