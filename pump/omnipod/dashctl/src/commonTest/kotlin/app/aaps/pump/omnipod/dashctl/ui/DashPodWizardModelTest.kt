package app.aaps.pump.omnipod.dashctl.ui

import app.aaps.core.data.model.ICfg
import app.aaps.core.data.model.TE
import app.aaps.pump.omnipod.dashctl.comm.io.SilentPodLogger
import app.aaps.pump.omnipod.dashctl.pod.definition.ActivationProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The wizard state, the rules of Android `OmnipodWizardViewModel` and its step composables: which
 * page shows, what each action does, what the buttons can do after a success or a failure, and the
 * errors shown. Nothing here loops by itself, so `runCurrent` runs each launched action to its end.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashPodWizardModelTest {

    private val pod = FakeWizardPod()
    private val host = FakeWizardHost()

    private fun TestScope.model() = DashPodWizardModel(pod, host, NameResolver, SilentPodLogger, this)

    private suspend fun TestScope.activation(): DashPodWizardModel = model().also {
        it.prepare()
        it.startActivation()
    }

    @Test
    fun `nothing shows before the wizard is started and ready says when it can be`() = runTest {
        val model = model()
        assertEquals(null, model.currentStep.value)
        assertFalse(model.ready.value)

        model.prepare()

        assertTrue(model.ready.value)
    }

    @Test
    fun `a new pod goes through every activation page and each part runs once`() = runTest {
        val model = activation()
        assertEquals(DashWizardStep.START_POD_ACTIVATION, model.currentStep.value)
        assertEquals(5, model.totalSteps.value)
        assertFalse(model.canGoBack.value, "leaving the wizard always asks first")

        model.moveToNext()
        assertEquals(DashWizardStep.INITIALIZE_POD, model.currentStep.value)
        model.executeAction()
        assertEquals(DashWizardActionState.Executing, model.actionState.value)
        runCurrent()
        assertEquals(DashWizardActionState.Success, model.actionState.value)
        assertEquals(listOf("part1"), pod.calls)

        model.moveToNext()
        assertEquals(DashWizardStep.ATTACH_POD, model.currentStep.value)
        assertEquals(DashWizardActionState.Idle, model.actionState.value, "each page starts idle")
        model.moveToNext()
        assertEquals(DashWizardStep.INSERT_CANNULA, model.currentStep.value)
        model.executeAction()
        runCurrent()
        assertEquals(listOf("part1", "part2"), pod.calls)

        model.moveToNext()
        assertEquals(DashWizardStep.POD_ACTIVATED, model.currentStep.value)
        assertEquals(4, model.currentStepIndex.value)
        model.completeActivation()
        runCurrent()
        assertTrue(model.finished.value)
        assertEquals(emptyList(), host.calls, "the active insulin stays, no site was chosen")
    }

    @Test
    fun `a pod stopped after the prime goes on with the cannula and is never primed again`() = runTest {
        pod.activationProgress = ActivationProgress.PHASE_1_COMPLETED
        val model = activation()

        assertEquals(DashWizardStep.ATTACH_POD, model.currentStep.value)
        assertEquals(3, model.totalSteps.value)
        model.moveToNext()
        model.executeAction()
        runCurrent()
        assertEquals(listOf("part2"), pod.calls)
    }

    @Test
    fun `a failed part shows the reason of the driver and Retry runs it again`() = runTest {
        pod.part1 = DashWizardResult(success = false, comment = "Could not connect to a new pod")
        val model = activation()
        model.moveToNext()

        model.executeAction()
        runCurrent()

        assertEquals(DashWizardActionState.Error("Could not connect to a new pod"), model.actionState.value)
        pod.part1 = DashWizardResult(success = true)
        model.executeAction()
        runCurrent()
        assertEquals(DashWizardActionState.Success, model.actionState.value)
        assertEquals(listOf("part1", "part1"), pod.calls)
    }

    @Test
    fun `a failure with no reason and an exception both show an error and never a success`() = runTest {
        pod.part1 = DashWizardResult(success = false, comment = "")
        val model = activation()
        model.moveToNext()
        model.executeAction()
        runCurrent()
        assertEquals(DashWizardActionState.Error("dash_wizard_unexpected_error"), model.actionState.value)

        pod.part1Throws = IllegalStateException("radio gone")
        model.executeAction()
        runCurrent()
        assertEquals(DashWizardActionState.Error("radio gone"), model.actionState.value)
    }

    @Test
    fun `a second press while a part runs sends nothing more and Next waits for the part`() = runTest {
        val model = activation()
        model.moveToNext()

        model.executeAction()
        model.executeAction()
        model.moveToNext()
        runCurrent()

        assertEquals(listOf("part1"), pod.calls, "one part 1 only")
        assertEquals(DashWizardStep.INITIALIZE_POD, model.currentStep.value, "Next does nothing while the part runs")
    }

    @Test
    fun `a page without an action sends nothing`() = runTest {
        val model = activation()

        model.executeAction()
        runCurrent()

        assertEquals(emptyList(), pod.calls)
        assertEquals(DashWizardActionState.Idle, model.actionState.value)
    }

    @Test
    fun `with no profile the gate shows first and a switch moves on`() = runTest {
        host.noProfileYet = true
        val model = activation()
        assertEquals(DashWizardStep.PROFILE_GATE, model.currentStep.value)
        assertEquals(listOf("Default", "Sport"), model.availableProfiles.value)
        assertEquals("Sport", model.selectedProfile.value, "the active profile is chosen first")

        model.selectProfile("Default")
        model.activateSelectedProfile()
        runCurrent()

        assertEquals(listOf("activateProfile(Default)"), host.calls)
        assertEquals(DashWizardStep.START_POD_ACTIVATION, model.currentStep.value)
    }

    @Test
    fun `a refused profile switch keeps the gate`() = runTest {
        host.noProfileYet = true
        host.profileSwitchWorks = false
        val model = activation()

        model.activateSelectedProfile()
        runCurrent()

        assertEquals(DashWizardStep.PROFILE_GATE, model.currentStep.value)
    }

    @Test
    fun `with two insulins the choice shows and the chosen one is switched to at the end`() = runTest {
        host.insulinList = listOf(ICfg("Fiasp", 5L, 1L), ICfg("Lyumjev", 5L, 1L))
        host.showSiteLocationStep = true
        val model = activation()
        assertEquals(DashWizardStep.START_POD_ACTIVATION, model.currentStep.value)
        model.moveToNext()
        assertEquals(DashWizardStep.SELECT_INSULIN, model.currentStep.value)
        assertEquals("Fiasp", model.selectedInsulin.value?.insulinLabel, "the active insulin first")
        model.selectInsulin(host.insulinList[1])

        model.moveToNext()
        model.executeAction()
        runCurrent()
        model.moveToNext()
        assertEquals(DashWizardStep.SITE_LOCATION, model.currentStep.value)
        model.updateSiteLocation(TE.Location.SIDE_RIGHT_UPPER_ABDOMEN)
        model.completeSiteLocation()
        assertEquals(DashWizardStep.ATTACH_POD, model.currentStep.value)
        model.moveToNext()
        model.executeAction()
        runCurrent()
        model.moveToNext()
        model.completeActivation()
        runCurrent()

        assertEquals(listOf("switchInsulin(Lyumjev)", "saveSiteLocation(SIDE_RIGHT_UPPER_ABDOMEN, null)"), host.calls)
    }

    @Test
    fun `a deactivation runs through the queue and ends on the deactivated page`() = runTest {
        val model = model()
        model.prepare()
        model.startDeactivation()
        assertEquals(DashWizardStep.START_POD_DEACTIVATION, model.currentStep.value)

        model.moveToNext()
        model.executeAction()
        runCurrent()
        model.moveToNext()

        assertEquals(listOf("deactivate"), pod.calls)
        assertEquals(DashWizardStep.POD_DEACTIVATED, model.currentStep.value)
        model.moveToNext()
        assertTrue(model.finished.value, "Next on the last page finishes")
    }

    @Test
    fun `a failed deactivation offers the discard which shows the discarded page`() = runTest {
        pod.deactivation = DashWizardResult(success = false, comment = "No answer from the pod")
        val model = model()
        model.prepare()
        model.startDeactivation()
        model.moveToNext()
        model.executeAction()
        runCurrent()
        assertIs<DashWizardActionState.Error>(model.actionState.value)

        model.discardPod()
        runCurrent()

        assertEquals(listOf("deactivate", "discard"), pod.calls)
        assertEquals(DashWizardStep.POD_DISCARDED, model.currentStep.value)
        assertEquals(DashWizardActionState.Idle, model.actionState.value)
    }

    @Test
    fun `leaving the profile gate closes the wizard`() = runTest {
        host.noProfileYet = true
        val model = activation()

        model.cancelGate()

        assertTrue(model.finished.value)
    }

    @Test
    fun `Finish closes the wizard only after the insulin switch and the site are saved`() = runTest {
        host.insulinList = listOf(ICfg("Fiasp", 5L, 1L), ICfg("Lyumjev", 5L, 1L))
        host.showSiteLocationStep = true
        val model = activation()
        model.moveToNext()
        model.selectInsulin(host.insulinList[1])
        model.moveToNext()
        model.executeAction()
        runCurrent()
        model.moveToNext()
        model.updateSiteLocation(TE.Location.SIDE_RIGHT_UPPER_ABDOMEN)
        model.completeSiteLocation()
        model.moveToNext()
        model.executeAction()
        runCurrent()
        model.moveToNext()
        assertEquals(DashWizardStep.POD_ACTIVATED, model.currentStep.value)
        val switchGate = CompletableDeferred<Unit>()
        val siteGate = CompletableDeferred<Unit>()
        host.switchGate = switchGate
        host.siteGate = siteGate

        model.completeActivation()
        runCurrent()
        assertFalse(model.finished.value, "closing now would cancel the insulin switch")
        model.completeActivation()
        runCurrent()
        assertEquals(listOf("switchInsulin(Lyumjev)"), host.calls, "a second tap does not switch twice")

        switchGate.complete(Unit)
        runCurrent()
        assertFalse(model.finished.value, "closing now would cancel the site save")
        siteGate.complete(Unit)
        runCurrent()

        assertTrue(model.finished.value)
        assertEquals(listOf("switchInsulin(Lyumjev)", "saveSiteLocation(SIDE_RIGHT_UPPER_ABDOMEN, null)"), host.calls)
    }

    @Test
    fun `a failed site save still closes the wizard`() = runTest {
        host.showSiteLocationStep = true
        val failing = object : DashWizardHost by host {
            override suspend fun saveSiteLocation(location: TE.Location, arrow: TE.Arrow?) = error("database closed")
        }
        val model = DashPodWizardModel(pod, failing, NameResolver, SilentPodLogger, this)
        model.prepare()
        model.startActivation()
        model.moveToNext()
        model.executeAction()
        runCurrent()
        model.moveToNext()
        model.updateSiteLocation(TE.Location.SIDE_RIGHT_UPPER_ABDOMEN)
        model.completeSiteLocation()
        model.moveToNext()
        model.executeAction()
        runCurrent()
        model.moveToNext()

        model.completeActivation()
        runCurrent()

        assertTrue(model.finished.value)
    }

    @Test
    fun `a second tap on Activate while the profile is set makes no second switch and skips no page`() = runTest {
        host.noProfileYet = true
        val gate = CompletableDeferred<Unit>()
        host.profileGate = gate
        val model = activation()
        assertEquals(DashWizardStep.PROFILE_GATE, model.currentStep.value)

        model.activateSelectedProfile()
        runCurrent()
        model.activateSelectedProfile()
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertEquals(listOf("activateProfile(Sport)"), host.calls)
        assertEquals(DashWizardStep.START_POD_ACTIVATION, model.currentStep.value, "the fill page is not skipped")
        assertEquals(emptyList(), pod.calls)
    }
}
