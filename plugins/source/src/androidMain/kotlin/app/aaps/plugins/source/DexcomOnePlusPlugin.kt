package app.aaps.plugins.source

import android.content.Context
import android.content.Intent
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.ble.BleRadioPriority
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.source.BgSource
import app.aaps.core.interfaces.source.CgmSensorLifecycle
import app.aaps.core.interfaces.source.CgmSensorStatusProvider
import app.aaps.core.interfaces.source.CgmStagingEvidence
import app.aaps.core.interfaces.source.CgmWarmupStatus
import app.aaps.core.interfaces.source.PromotionRejectReason
import app.aaps.core.interfaces.source.PromotionResult
import app.aaps.core.interfaces.source.SensorSlot
import app.aaps.core.interfaces.source.StagingState
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.keys.interfaces.withClick
import app.aaps.core.ui.compose.icons.IcPluginByoda
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.plugins.dexcomoneplus.OnePlusCgmDrivers
import app.aaps.plugins.dexcomoneplus.OnePlusCgmDriverReal
import app.aaps.plugins.dexcomoneplus.oem.DeviceProfileRegistry
import app.aaps.plugins.dexcomoneplus.OnePlusGlucoseSample
import app.aaps.plugins.dexcomoneplus.OnePlusGlucoseWatcher
import app.aaps.plugins.dexcomoneplus.OnePlusWarmupState
import app.aaps.plugins.dexcomoneplus.identity.OnePlusSensorStore
import app.aaps.plugins.source.activities.DexcomOnePlusStartActivity
import app.aaps.plugins.source.activities.DexcomOnePlusStatusActivity
import app.aaps.plugins.source.activities.DexcomOnePlusWarmupActivity
import app.aaps.plugins.source.compose.BgSourceComposeContent
import app.aaps.plugins.source.keys.DexcomOnePlusBooleanKey
import app.aaps.plugins.source.keys.DexcomOnePlusIntentKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.IntKey
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Native Dexcom ONE+ BG source (in-process BLE driver).
 *
 * Default driver is Stub; engineering pref can select Real skeleton (still fail-closed).
 * See docs/DEXCOM_ONEPLUS_NATIVE_PLUGIN_PRODUCT.md.
 *
 * Registers itself into the plugin list. Scoped with Metro's own [SingleIn], not javax `@Singleton`.
 */
@ContributesIntoMap(AppScope::class, binding = binding<PluginBase>())
@IntKey(446)
@SingleIn(AppScope::class)
class DexcomOnePlusPlugin @Inject constructor(
    rh: ResourceHelper,
    aapsLogger: AAPSLogger,
    preferences: Preferences,
    config: Config,
    private val context: Context,
    private val persistenceLayer: PersistenceLayer,
    private val warmupBasalGuard: DexcomOnePlusWarmupBasalGuard,
    private val availabilityProvider: DexcomOnePlusAvailabilityProvider,
    private val bleRadioPriority: BleRadioPriority,
    private val activePlugin: ActivePlugin,
) : AbstractBgSourcePlugin(
    pluginDescription = PluginDescription()
        .mainType(PluginType.BGSOURCE)
        .composeContent { plugin ->
            BgSourceComposeContent(
                title = rh.gs(R.string.dexcom_oneplus_native),
            )
        }
        .icon(IcPluginByoda)
        .pluginName(TextRef.AndroidRes(R.string.dexcom_oneplus_native))
        .shortName(TextRef.AndroidRes(R.string.dexcom_oneplus_short))
        .preferencesVisibleInSimpleMode(false)
        .description(TextRef.AndroidRes(R.string.description_source_dexcom_oneplus_native)),
    ownPreferences = DexcomOnePlusIntentKey.entries + DexcomOnePlusBooleanKey.entries,
    aapsLogger,
    rh,
    preferences,
    config,
), BgSource, OnePlusGlucoseWatcher, CgmSensorStatusProvider {

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val driver get() = OnePlusCgmDrivers.default()
    private val stagingDriver: OnePlusCgmDriverReal get() = OnePlusCgmDrivers.staging()
    override suspend fun promoteStagingToProduction(allowEarly: Boolean): PromotionResult {
        if (!stagingPresent) return rejectPromotion(PromotionRejectReason.STAGING_ABSENT, allowEarly)
        return PromotionResult.Ok
    }
}
