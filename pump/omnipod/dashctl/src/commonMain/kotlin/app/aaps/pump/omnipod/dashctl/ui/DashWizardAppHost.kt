package app.aaps.pump.omnipod.dashctl.ui

import app.aaps.core.data.model.ICfg
import app.aaps.core.data.model.TE
import app.aaps.core.data.model.labelOfInsulin
import app.aaps.core.data.time.T
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.insulin.InsulinManager
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileRepository
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.pump.activateProfileFromGate
import app.aaps.core.ui.compose.pump.switchToWizardInsulin
import app.aaps.core.ui.compose.siteRotation.BodyType
import kotlinx.coroutines.delay

/**
 * The [DashWizardHost] of the app: the same calls as the Android Dash wizard
 * (`OmnipodWizardViewModel`, `DashOmnipodWizardViewModel`), on the shared interfaces.
 *
 * One difference, taken from `MedtrumPatchWizardModel`: after the profile gate, [activateProfile]
 * waits (at most one minute) until the queue has set the new profile. Part 2 reads the basal
 * program from `PumpSync.expectedPumpState().profile`, which the queue writes after the switch, so
 * without the wait the cannula step could fail with "No profile set". Android does not wait; there the
 * person reads the next pages first, which takes longer than the switch.
 */
class DashWizardAppHost(
    private val profileFunction: ProfileFunction,
    private val profileRepository: ProfileRepository,
    private val insulinManager: InsulinManager,
    private val persistenceLayer: PersistenceLayer,
    private val pumpSync: PumpSync,
    private val preferences: Preferences,
    private val rh: TextResolver,
    private val rxBus: RxBus,
    private val dateUtil: DateUtil
) : DashWizardHost {

    override suspend fun needsProfileGate(): Boolean = profileFunction.getRequestedProfile() == null

    override suspend fun profileNames(): List<String> = profileRepository.profiles.value.map { it.name }

    override suspend fun activeProfileName(): String = profileFunction.getOriginalProfileName()

    override suspend fun activateProfile(name: String): Boolean {
        val store = profileRepository.profile.value ?: return false
        // Android `fallbackICfg`: the first insulin set up.
        val iCfg = insulinManager.insulins.firstOrNull() ?: return false
        if (!activateProfileFromGate(profileFunction, rh, rxBus, store, name, iCfg, Sources.OmnipodDash, dateUtil.now())) return false
        val deadline = dateUtil.now() + PROFILE_WAIT_MS
        while (pumpSync.expectedPumpState().profile == null && dateUtil.now() < deadline) delay(PROFILE_READ_INTERVAL_MS)
        return true
    }

    override suspend fun insulins(): List<ICfg> = insulinManager.insulins.map { it.deepClone() }

    override suspend fun activeInsulinLabel(insulins: List<ICfg>): String? = insulins.labelOfInsulin(profileFunction.getProfile()?.iCfg)

    override suspend fun switchInsulin(iCfg: ICfg) {
        switchToWizardInsulin(profileFunction, rh, rxBus, iCfg, Sources.OmnipodDash)
    }

    override val showSiteLocationStep: Boolean get() = preferences.get(BooleanKey.SiteRotationManagePump)

    override val concentrationEnabled: Boolean get() = preferences.get(BooleanKey.GeneralInsulinConcentration)

    override fun bodyType(): BodyType = BodyType.fromPref(preferences.get(IntKey.SiteRotationUserProfile))

    override suspend fun siteRotationEntries(): List<TE> =
        persistenceLayer.getTherapyEventDataFromTime(dateUtil.now() - T.days(45).msecs(), false)
            .filter { it.type == TE.Type.CANNULA_CHANGE || it.type == TE.Type.SENSOR_CHANGE }

    override suspend fun saveSiteLocation(location: TE.Location, arrow: TE.Arrow?) {
        val now = dateUtil.now()
        persistenceLayer.getTherapyEventDataFromToTime(now - 60_000, now)
            .firstOrNull { it.type == TE.Type.CANNULA_CHANGE }
            ?.let { persistenceLayer.insertOrUpdateTherapyEvent(it.copy(location = location, arrow = arrow)) }
    }

    private companion object {

        const val PROFILE_WAIT_MS = 60_000L
        const val PROFILE_READ_INTERVAL_MS = 500L
    }
}
