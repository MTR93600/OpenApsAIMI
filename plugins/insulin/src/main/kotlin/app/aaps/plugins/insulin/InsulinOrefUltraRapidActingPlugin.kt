package app.aaps.plugins.insulin

import android.content.Context
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.insulin.Insulin
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.interfaces.Preferences
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Created by adrian on 14/08/17.
 */
@Singleton
class InsulinOrefUltraRapidActingPlugin @Inject constructor(
    rh: ResourceHelper,
    val preferences: Preferences,
    aapsSchedulers: AapsSchedulers,
    fabricPrivacy: FabricPrivacy,
    persistenceLayer: PersistenceLayer,
    profileFunction: ProfileFunction,
    rxBus: RxBus,
    aapsLogger: AAPSLogger,
    config: Config,
    hardLimits: HardLimits,
    uiInteraction: UiInteraction,
    context: Context
) : InsulinOrefBasePlugin(rh, preferences, aapsSchedulers, fabricPrivacy, persistenceLayer, profileFunction, rxBus, aapsLogger, config, hardLimits, uiInteraction, context) {

    override val id get(): Insulin.InsulinType = Insulin.InsulinType.OREF_ULTRA_RAPID_ACTING
    override val friendlyName get(): String = rh.gs(R.string.ultra_rapid_oref)

    override fun configuration(): JSONObject = JSONObject()
    override fun applyConfiguration(configuration: JSONObject) {}

    override fun commentStandardText(): String = rh.gs(R.string.ultra_fast_acting_insulin_comment)

    override val peak = 55

    init {
        pluginDescription
            .pluginName(R.string.ultra_rapid_oref)
            .description(R.string.description_insulin_ultra_rapid)
        if(concentrationEnabled)
            pluginDescription.preferencesId(PluginDescription.PREFERENCE_SCREEN)
    }
}