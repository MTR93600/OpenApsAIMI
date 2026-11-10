package app.aaps.ui.compose.quickLaunch

import app.aaps.core.interfaces.automation.Automation
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileRepository
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.scenes.SceneStore
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.wizard.QuickWizard
import app.aaps.ui.compose.navigation.ElementAvailability
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

internal class QuickLaunchResolverTest {

    @Mock private lateinit var preferences: Preferences
    @Mock private lateinit var quickWizard: QuickWizard
    @Mock private lateinit var automation: Automation
    @Mock private lateinit var activePlugin: ActivePlugin
    @Mock private lateinit var profileRepository: ProfileRepository
    @Mock private lateinit var sceneRepository: SceneStore
    @Mock private lateinit var rh: ResourceHelper
    @Mock private lateinit var elementAvailability: ElementAvailability

    private lateinit var sut: QuickLaunchResolver

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        // resolveItem asks rh for the button label; the tests below only read `enabled`.
        whenever(rh.gs(any<Int>())).thenReturn("")
        sut = QuickLaunchResolver(
            preferences, quickWizard, automation, activePlugin, profileRepository,
            sceneRepository, rh, elementAvailability
        )
    }

    // A saved static button must survive even when its source plugin is not active, because
    // MainViewModel.refreshQuickLaunch deletes every entry isValid() rejects.
    @Test
    fun `static actions stay valid when their source plugin is not active`() {
        whenever(elementAvailability.isAvailable(ElementType.CALIBRATION)).thenReturn(false)
        whenever(elementAvailability.isAvailable(ElementType.CGM_XDRIP)).thenReturn(false)

        assertThat(sut.isValid(QuickLaunchAction.Calibration)).isTrue()
        assertThat(sut.isValid(QuickLaunchAction.Cgm)).isTrue()
        assertThat(sut.isValid(QuickLaunchAction.Afrezza)).isTrue()
        assertThat(sut.isValid(QuickLaunchAction.EversenseCalibration)).isTrue()
    }

    // ... but it renders greyed out, so the user can see it is not usable right now.
    @Test
    fun `an unavailable static action resolves as not enabled`() {
        whenever(elementAvailability.isAvailable(ElementType.CALIBRATION)).thenReturn(false)

        assertThat(sut.resolveItem(QuickLaunchAction.Calibration).enabled).isFalse()
    }

    @Test
    fun `an available static action resolves as enabled`() {
        whenever(elementAvailability.isAvailable(ElementType.CALIBRATION)).thenReturn(true)

        assertThat(sut.resolveItem(QuickLaunchAction.Calibration).enabled).isTrue()
    }

    @Test
    fun `a quick wizard action with an unknown guid is not valid`() {
        assertThat(sut.isValid(QuickLaunchAction.QuickWizardAction("does-not-exist"))).isFalse()
    }
}
