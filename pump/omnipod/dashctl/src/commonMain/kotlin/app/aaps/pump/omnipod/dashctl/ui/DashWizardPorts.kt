package app.aaps.pump.omnipod.dashctl.ui

import app.aaps.core.data.model.ICfg
import app.aaps.core.data.model.TE
import app.aaps.core.ui.compose.siteRotation.BodyType
import app.aaps.pump.omnipod.dashctl.pod.definition.ActivationProgress

/**
 * What the wizard asks of the pod. On the iPhone this is the Dash plugin (`IosDashWizardPod`): the
 * two activation parts and the discard as the Android wizard calls them on the manager, and the
 * deactivation through the queue (Android `commandQueue.customCommand(CommandDeactivatePod())`).
 *
 * Each call returns its result; it never throws for a pod error. The stored activation progress says
 * where to go on after a failure, and calling a part again goes on from there.
 */
interface DashWizardPod {

    /** The stored activation progress of the pod (Android `podStateManager.activationProgress`). */
    val activationProgress: ActivationProgress

    /** Android `doInitializePod`: pair, prime, settings in. */
    suspend fun activatePart1(): DashWizardResult

    /** Android `doInsertCannula`: basal program, cannula, and what Android writes after it. */
    suspend fun activatePart2(): DashWizardResult

    /** Android `doDeactivatePod`: the deactivation, queued. */
    suspend fun deactivate(): DashWizardResult

    /** Android `discardPod`: the pod state is cleared, nothing is sent. */
    suspend fun discard()
}

/** What a pod call of the wizard gave: success, or the reason it failed (`PumpEnactResult.comment`). */
data class DashWizardResult(val success: Boolean, val comment: String = "")

/**
 * What the wizard asks of the rest of the app: the steps of the Android wizard that send nothing to
 * the pod (profile gate, insulin choice, site location). The real one is [DashWizardAppHost]; tests
 * give a small fake, so the step logic is tested without the database and the profile store.
 */
interface DashWizardHost {

    /** Android `profileFunction.getRequestedProfile() == null`: the wizard first asks for a profile. */
    suspend fun needsProfileGate(): Boolean

    /** The names of the profiles in the profile store. */
    suspend fun profileNames(): List<String>

    /** The name of the active profile, without the percentage and time shift. */
    suspend fun activeProfileName(): String

    /**
     * Activates [name] (Android `activateProfileFromGate`) and waits until the queue has set it, so
     * part 2 finds a basal program. False when it was refused; the person was told why.
     */
    suspend fun activateProfile(name: String): Boolean

    /** The insulins the person set up, as copies. */
    suspend fun insulins(): List<ICfg>

    /** The label of the insulin of the active profile, or null. */
    suspend fun activeInsulinLabel(insulins: List<ICfg>): String?

    /** Android `executeInsulinProfileSwitch`: a profile switch to [iCfg]. A refusal says why. */
    suspend fun switchInsulin(iCfg: ICfg)

    /** Android `preferences.get(BooleanKey.SiteRotationManagePump)`. */
    val showSiteLocationStep: Boolean

    /** Android `preferences.get(BooleanKey.GeneralInsulinConcentration)`. */
    val concentrationEnabled: Boolean

    fun bodyType(): BodyType

    /** The cannula and sensor changes of the last 45 days, for the site picture. */
    suspend fun siteRotationEntries(): List<TE>

    /** Android `saveSiteLocation`: puts the site on the cannula change of the last minute. */
    suspend fun saveSiteLocation(location: TE.Location, arrow: TE.Arrow?)
}
