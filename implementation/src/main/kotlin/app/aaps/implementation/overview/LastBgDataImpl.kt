package app.aaps.implementation.overview

import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.overview.LastBgData
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.fromGv
import app.aaps.core.objects.extensions.valueToUnits
import dagger.Reusable
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@Reusable
class LastBgDataImpl @Inject constructor(
    private val rh: ResourceHelper,
    private val dateUtil: DateUtil,
    private val persistenceLayer: PersistenceLayer,
    private val profileFunction: ProfileFunction,
    private val preferences: Preferences,
    private val iobCobCalculator: IobCobCalculator
) : LastBgData {

    /**
     * Always prefer the in-memory bucketed (smoothed + calibrated) glucose value and only fall back to the
     * raw database value when no bucketed value is available at all (for example right after app start,
     * before the bucketed data has been built for the first time).
     *
     * We do NOT pick whichever of the two has the newer timestamp: for a few seconds after every new CGM
     * reading, the raw DB value can be newer than the bucketed value while the bucketed pipeline is still
     * rebuilding, but the DB value is un-smoothed and un-calibrated. Using it during that short window made
     * this differ from the Overview screen (`OverviewDataCacheImpl`, which already prefers bucketed data),
     * the home-screen widget, and the Garmin watch sync. Preferring bucketed data here keeps all of these
     * showing the same BG number.
     */
    override fun lastBg(): InMemoryGlucoseValue? =
        iobCobCalculator.ads.lastBg()
            ?: runBlocking { persistenceLayer.getLastGlucoseValue() }?.let { InMemoryGlucoseValue.fromGv(it) }

    override fun isLow(): Boolean =
        lastBg()?.let { lastBg ->
            lastBg.valueToUnits(profileFunction.getUnits()) < preferences.get(UnitDoubleKey.OverviewLowMark)
        } == true

    override fun isHigh(): Boolean =
        lastBg()?.let { lastBg ->
            lastBg.valueToUnits(profileFunction.getUnits()) > preferences.get(UnitDoubleKey.OverviewHighMark)
        } == true

    override fun lastBgDescription(): String =
        when {
            isLow()  -> rh.gs(app.aaps.core.ui.R.string.a11y_low)
            isHigh() -> rh.gs(app.aaps.core.ui.R.string.a11y_high)
            else     -> rh.gs(app.aaps.core.ui.R.string.a11y_inrange)
        }

    override fun isActualBg(): Boolean =
        lastBg()?.let { lastBg ->
            lastBg.timestamp > dateUtil.now() - T.mins(9).msecs()
        } == true
}
