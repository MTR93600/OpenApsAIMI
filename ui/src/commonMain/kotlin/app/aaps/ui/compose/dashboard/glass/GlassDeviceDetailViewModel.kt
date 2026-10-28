package app.aaps.ui.compose.dashboard.glass

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.data.model.GV
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.AdaptiveSmoothingQualitySnapshot
import app.aaps.core.interfaces.rx.events.AdaptiveSmoothingQualityTier
import app.aaps.core.interfaces.rx.events.EventAdaptiveSmoothingQuality
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.pump.tickerFlow
import app.aaps.ui.UiStrings
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn

/**
 * The values of the Glass pump, battery and sensor quality screens. Display only.
 *
 * On `dev_OAPSAIMI` these came from `OverviewViewModel.statusCardState` (`reservoirText`,
 * `pumpBatteryText`, `adaptiveSmoothingQuality*`). The sources are the same: the active pump and the
 * last quality snapshot of the active smoothing plugin.
 */
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
@Stable
@Inject
class GlassDeviceDetailViewModel(
    private val activePlugin: ActivePlugin,
    persistenceLayer: PersistenceLayer,
    rxBus: RxBus,
    private val rh: TextResolver,
) : ViewModel() {

    /** The smoothing plugin has no flow: read again when it reports, on new glucose, and every minute. */
    private val smoothingQuality = merge(
        tickerFlow(60_000L),
        persistenceLayer.observeChanges(GV::class).map { },
        rxBus.toFlow(EventAdaptiveSmoothingQuality::class).map { },
    ).map { activePlugin.activeSmoothing.lastAdaptiveSmoothingQualitySnapshot()?.let(::sensorQuality) }

    val state: StateFlow<GlassDeviceDetailState> = combine(
        activePlugin.activePump.reservoirLevel,
        activePlugin.activePump.batteryLevel,
        smoothingQuality,
    ) { reservoir, battery, quality ->
        GlassDeviceDetailState(
            reservoirText = reservoir.cU.takeIf { it > 0 }?.let { rh.gs(InterfacesStrings.format_insulin_units, it) },
            batteryText = battery?.let { rh.gs(CoreUiStrings.units_format_percent, it) },
            sensorQuality = quality,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), GlassDeviceDetailState())

    private fun sensorQuality(snapshot: AdaptiveSmoothingQualitySnapshot): GlassSensorQuality {
        val texts = adaptiveSmoothingQualityTexts(snapshot.tier)
        return GlassSensorQuality(
            tier = snapshot.tier,
            badgeText = rh.gs(texts.badge),
            dialogMessage = rh.gs(
                texts.dialog,
                snapshot.learnedR,
                (snapshot.outlierRate * 100.0).toInt(),
                (snapshot.compressionRate * 100.0).toInt(),
            ),
        )
    }
}

/** Texts are ready to show; null means "no value", and the screen then shows "--". */
data class GlassDeviceDetailState(
    val reservoirText: String? = null,
    val batteryText: String? = null,
    /** Null when the active smoothing plugin reports no quality (only Adaptive Smoothing does). */
    val sensorQuality: GlassSensorQuality? = null,
)

data class GlassSensorQuality(
    val tier: AdaptiveSmoothingQualityTier,
    val badgeText: String,
    val dialogMessage: String,
)

internal data class AdaptiveSmoothingQualityTexts(val badge: TextRef, val dialog: TextRef)

/** The badge and message strings of a quality tier, as on `dev_OAPSAIMI`. */
internal fun adaptiveSmoothingQualityTexts(tier: AdaptiveSmoothingQualityTier): AdaptiveSmoothingQualityTexts = when (tier) {
    AdaptiveSmoothingQualityTier.OK        -> AdaptiveSmoothingQualityTexts(UiStrings.adaptive_smoothing_quality_badge_ok, UiStrings.adaptive_smoothing_quality_dialog_ok)
    AdaptiveSmoothingQualityTier.UNCERTAIN -> AdaptiveSmoothingQualityTexts(UiStrings.adaptive_smoothing_quality_badge_uncertain, UiStrings.adaptive_smoothing_quality_dialog_uncertain)
    AdaptiveSmoothingQualityTier.BAD       -> AdaptiveSmoothingQualityTexts(UiStrings.adaptive_smoothing_quality_badge_bad, UiStrings.adaptive_smoothing_quality_dialog_bad)
}
