package app.aaps.pump.omnipod.dashctl.ui

import app.aaps.core.data.model.ICfg
import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.compose.siteRotation.BodyType
import app.aaps.pump.omnipod.dashctl.pod.definition.ActivationProgress
import app.aaps.pump.omnipod.dashctl.session.DashPodState
import app.aaps.pump.omnipod.dashctl.session.DashPodStateManager
import app.aaps.pump.omnipod.dashctl.session.DashPodStateStore
import kotlinx.coroutines.CompletableDeferred

/** Resolves a text to its name and its arguments, so a test reads which text was used. */
internal object NameResolver : TextResolver {

    override fun gs(ref: TextRef): String = when (ref) {
        is TextRef.Named      -> if (ref.args.isEmpty()) ref.name else "${ref.name}${ref.args}"
        is TextRef.Literal    -> ref.text
        is TextRef.AndroidRes -> "res${ref.id}"
    }

    override fun gs(ref: TextRef, vararg args: Any?): String = when (ref) {
        is TextRef.Named -> "${ref.name}${args.toList()}"
        else             -> gs(ref)
    }

    override fun gsNotLocalised(ref: TextRef): String = gs(ref)
}

/** A pod state manager that starts from [state] and keeps writes in memory. */
internal fun stateManagerWith(state: DashPodState): DashPodStateManager = DashPodStateManager(object : DashPodStateStore {
    var saved: DashPodState? = state
    override fun load(): DashPodState? = saved
    override fun save(state: DashPodState) {
        saved = state
    }

    override fun clear() {
        saved = null
    }
})

/** A [DashWizardPod] that answers what the test sets, and keeps every call. */
internal class FakeWizardPod(override var activationProgress: ActivationProgress = ActivationProgress.NOT_STARTED) : DashWizardPod {

    val calls = mutableListOf<String>()
    var part1 = DashWizardResult(success = true)
    var part2 = DashWizardResult(success = true)
    var deactivation = DashWizardResult(success = true)

    /** Thrown by the next part 1 instead of an answer. */
    var part1Throws: Exception? = null

    override suspend fun activatePart1(): DashWizardResult {
        calls += "part1"
        part1Throws?.let { throw it }
        return part1
    }

    override suspend fun activatePart2(): DashWizardResult {
        calls += "part2"
        return part2
    }

    override suspend fun deactivate(): DashWizardResult {
        calls += "deactivate"
        return deactivation
    }

    override suspend fun discard() {
        calls += "discard"
    }
}

/** A [DashWizardHost] with the answers the test sets, keeping every call that changes something. */
internal class FakeWizardHost : DashWizardHost {

    val calls = mutableListOf<String>()
    var noProfileYet = false
    var profiles = listOf("Default", "Sport")
    var activeProfile = "Sport"
    var profileSwitchWorks = true
    var insulinList = listOf(ICfg("Fiasp", 5L, 1L))
    var activeInsulin: String? = "Fiasp"
    override var showSiteLocationStep = false
    override var concentrationEnabled = false

    /** When set, a profile switch waits for it, as the real one waits for the queue to set the profile. */
    var profileGate: CompletableDeferred<Unit>? = null

    /** When set, the insulin switch waits for it, as the real one waits for the database. */
    var switchGate: CompletableDeferred<Unit>? = null

    /** When set, the site save waits for it, as the real one waits for the database. */
    var siteGate: CompletableDeferred<Unit>? = null

    override suspend fun needsProfileGate(): Boolean = noProfileYet
    override suspend fun profileNames(): List<String> = profiles
    override suspend fun activeProfileName(): String = activeProfile
    override suspend fun activateProfile(name: String): Boolean {
        calls += "activateProfile($name)"
        profileGate?.await()
        return profileSwitchWorks
    }

    override suspend fun insulins(): List<ICfg> = insulinList
    override suspend fun activeInsulinLabel(insulins: List<ICfg>): String? = activeInsulin
    override suspend fun switchInsulin(iCfg: ICfg) {
        calls += "switchInsulin(${iCfg.insulinLabel})"
        switchGate?.await()
    }

    override fun bodyType(): BodyType = BodyType.entries.first()
    override suspend fun siteRotationEntries(): List<TE> = emptyList()
    override suspend fun saveSiteLocation(location: TE.Location, arrow: TE.Arrow?) {
        calls += "saveSiteLocation($location, $arrow)"
        siteGate?.await()
    }
}
