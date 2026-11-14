package app.aaps.pump.omnipod.dashctl.ui

import app.aaps.pump.omnipod.dashctl.pod.definition.ActivationProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which wizard pages show, the rules of Android `OmnipodWizardViewModel.initializeActivation` and
 * `DashOverviewViewModel.onActivatePodClicked`.
 */
class DashWizardStepTest {

    @Test
    fun `before the prime the whole activation shows and from the prime on only part 2`() {
        val short = ActivationProgress.entries.filter { it.isAtLeast(ActivationProgress.PRIME_COMPLETED) }
        ActivationProgress.entries.forEach { progress ->
            val expected = if (progress in short) DashActivationType.SHORT else DashActivationType.LONG
            assertEquals(expected, dashActivationType(progress), "progress $progress")
        }
        // Android: SHORT from PRIME_COMPLETED on, so a stopped part 2 goes on with the cannula step.
        assertEquals(DashActivationType.LONG, dashActivationType(ActivationProgress.PRIMING))
        assertEquals(DashActivationType.SHORT, dashActivationType(ActivationProgress.PRIME_COMPLETED))
        assertEquals(DashActivationType.SHORT, dashActivationType(ActivationProgress.CANNULA_INSERTED))
    }

    @Test
    fun `the long activation has every page in the Android order`() {
        assertEquals(
            listOf(
                DashWizardStep.PROFILE_GATE,
                DashWizardStep.START_POD_ACTIVATION,
                DashWizardStep.SELECT_INSULIN,
                DashWizardStep.INITIALIZE_POD,
                DashWizardStep.SITE_LOCATION,
                DashWizardStep.ATTACH_POD,
                DashWizardStep.INSERT_CANNULA,
                DashWizardStep.POD_ACTIVATED
            ),
            dashActivationSteps(DashActivationType.LONG, needsProfileGate = true, showInsulinStep = true, showSiteLocationStep = true)
        )
    }

    @Test
    fun `the optional pages show only when asked`() {
        assertEquals(
            listOf(
                DashWizardStep.START_POD_ACTIVATION,
                DashWizardStep.INITIALIZE_POD,
                DashWizardStep.ATTACH_POD,
                DashWizardStep.INSERT_CANNULA,
                DashWizardStep.POD_ACTIVATED
            ),
            dashActivationSteps(DashActivationType.LONG, needsProfileGate = false, showInsulinStep = false, showSiteLocationStep = false)
        )
    }

    @Test
    fun `the short activation starts at the attach page and never primes again`() {
        val steps = dashActivationSteps(DashActivationType.SHORT, needsProfileGate = false, showInsulinStep = true, showSiteLocationStep = false)

        assertEquals(listOf(DashWizardStep.ATTACH_POD, DashWizardStep.INSERT_CANNULA, DashWizardStep.POD_ACTIVATED), steps)
    }

    @Test
    fun `the deactivation has three pages and the discard page replaces the last one`() {
        assertEquals(listOf(DashWizardStep.START_POD_DEACTIVATION, DashWizardStep.DEACTIVATE_POD, DashWizardStep.POD_DEACTIVATED), DASH_DEACTIVATION_STEPS)
    }

    @Test
    fun `only part 1 part 2 and the deactivation send something to the pod`() {
        val action = DashWizardStep.entries.filter { it.isActionStep }

        assertEquals(listOf(DashWizardStep.INITIALIZE_POD, DashWizardStep.INSERT_CANNULA, DashWizardStep.DEACTIVATE_POD), action)
    }

    @Test
    fun `every page has a title and the pages that draw no text of their own have one`() {
        DashWizardStep.entries.forEach { step ->
            assertNotNull(step.title, "title of $step")
            if (step == DashWizardStep.PROFILE_GATE || step == DashWizardStep.SITE_LOCATION) assertNull(step.text, "text of $step")
            else assertTrue(NameResolver.gs(assertNotNull(step.text)).isNotEmpty(), "text of $step")
        }
    }
}
