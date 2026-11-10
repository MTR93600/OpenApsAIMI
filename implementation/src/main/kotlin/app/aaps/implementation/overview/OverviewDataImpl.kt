package app.aaps.implementation.overview

import android.content.Context
import androidx.annotation.AttrRes
import androidx.annotation.DrawableRes
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.GV
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.graph.Scale
import app.aaps.core.interfaces.graph.SeriesData
import app.aaps.core.interfaces.overview.OverviewData
import app.aaps.core.interfaces.overview.graph.BgDataPoint
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant

@Singleton
class OverviewDataImpl @Inject constructor() : OverviewData {
    private class EmptySeriesData : SeriesData

    override var rangeToDisplay: Int = Constants.GRAPH_TIME_RANGE_HOURS

    // Initialize the window anchor so workers reading these fields before the
    // first predictions-prep run see sensible values. Rounded to next
    // full hour + GraphView-era 100ms nudge to avoid axis-label rounding.
    override var toTime: Long = initialToTime()
    override var fromTime: Long = toTime - T.hours(Constants.GRAPH_TIME_RANGE_HOURS.toLong()).msecs()
    override var endTime: Long = toTime
    override var pumpStatus: String = ""
    override var calcProgressPct: Int = 100

    override var bgReadingsArray: List<GV> = ArrayList()
    override var maxBgValue: Double = Double.MIN_VALUE
    override var bucketedGraphSeries: SeriesData = EmptySeriesData()
    override var bgReadingGraphSeries: SeriesData = EmptySeriesData()
    override var predictionsGraphSeries: SeriesData = EmptySeriesData()

    override fun replacePredictionGraphSeriesFromWorker(points: List<BgDataPoint>) {
        // Stub implementation: real graph data lives in plugins/main OverviewDataImpl.
    }

    override val basalScale: Scale = Scale()
    override var baseBasalGraphSeries: SeriesData = EmptySeriesData()
    override var tempBasalGraphSeries: SeriesData = EmptySeriesData()
    override var basalLineGraphSeries: SeriesData = EmptySeriesData()
    override var absoluteBasalGraphSeries: SeriesData = EmptySeriesData()

    override var temporaryTargetSeries: SeriesData = EmptySeriesData()
    override var runningModesSeries: SeriesData = EmptySeriesData()

    override var maxIAValue: Double = 0.0
    override val actScale: Scale = Scale()
    override var activitySeries: SeriesData = EmptySeriesData()
    override var activityPredictionSeries: SeriesData = EmptySeriesData()

    override var maxEpsValue: Double = 0.0
    override val epsScale: Scale = Scale()
    override var epsSeries: SeriesData = EmptySeriesData()
    override var maxTreatmentsValue: Double = 0.0
    override var treatmentsSeries: SeriesData = EmptySeriesData()
    override var maxTherapyEventValue: Double = 0.0
    override var therapyEventSeries: SeriesData = EmptySeriesData()

    override var maxIobValueFound: Double = Double.MIN_VALUE
    override val iobScale: Scale = Scale()
    override var iobSeries: SeriesData = EmptySeriesData()
    override var absIobSeries: SeriesData = EmptySeriesData()
    override var iobPredictions1Series: SeriesData = EmptySeriesData()

    override var maxBGIValue: Double = Double.MIN_VALUE
    override val bgiScale: Scale = Scale()
    override var minusBgiSeries: SeriesData = EmptySeriesData()
    override var minusBgiHistSeries: SeriesData = EmptySeriesData()

    override var maxCobValueFound: Double = Double.MIN_VALUE
    override val cobScale: Scale = Scale()
    override var cobSeries: SeriesData = EmptySeriesData()
    override var cobMinFailOverSeries: SeriesData = EmptySeriesData()

    override var maxDevValueFound: Double = Double.MIN_VALUE
    override val devScale: Scale = Scale()
    override var deviationsSeries: SeriesData = EmptySeriesData()

    override var maxRatioValueFound: Double = 5.0
    override var minRatioValueFound: Double = -maxRatioValueFound
    override val ratioScale: Scale = Scale()
    override var ratioSeries: SeriesData = EmptySeriesData()

    override var maxVarSensValueFound: Double = 200.0
    override var minVarSensValueFound: Double = 50.0
    override val varSensScale: Scale = Scale()
    override var varSensSeries: SeriesData = EmptySeriesData()

    override var maxFromMaxValueFound: Double = Double.MIN_VALUE
    override var maxFromMinValueFound: Double = Double.MIN_VALUE
    override val dsMaxScale: Scale = Scale()
    override val dsMinScale: Scale = Scale()
    override var dsMaxSeries: SeriesData = EmptySeriesData()
    override var dsMinSeries: SeriesData = EmptySeriesData()
    override var heartRateScale: Scale = Scale()
    override var heartRateGraphSeries: SeriesData = EmptySeriesData()
    override var stepsForScale: Scale = Scale()
    override var stepsCountGraphSeries: SeriesData = EmptySeriesData()

    override fun reset() {
        pumpStatus = ""
        calcProgressPct = 100
        bgReadingsArray = ArrayList()
        maxBgValue = Double.MIN_VALUE
        bucketedGraphSeries = EmptySeriesData()
        bgReadingGraphSeries = EmptySeriesData()
        predictionsGraphSeries = EmptySeriesData()
        baseBasalGraphSeries = EmptySeriesData()
        tempBasalGraphSeries = EmptySeriesData()
        basalLineGraphSeries = EmptySeriesData()
        absoluteBasalGraphSeries = EmptySeriesData()
        temporaryTargetSeries = EmptySeriesData()
        runningModesSeries = EmptySeriesData()
        maxIAValue = 0.0
        activitySeries = EmptySeriesData()
        activityPredictionSeries = EmptySeriesData()
        maxIobValueFound = Double.MIN_VALUE
        iobSeries = EmptySeriesData()
        absIobSeries = EmptySeriesData()
        iobPredictions1Series = EmptySeriesData()
        maxBGIValue = Double.MIN_VALUE
        minusBgiSeries = EmptySeriesData()
        minusBgiHistSeries = EmptySeriesData()
        maxCobValueFound = Double.MIN_VALUE
        cobSeries = EmptySeriesData()
        cobMinFailOverSeries = EmptySeriesData()
        maxDevValueFound = Double.MIN_VALUE
        deviationsSeries = EmptySeriesData()
        maxRatioValueFound = 5.0
        minRatioValueFound = -maxRatioValueFound
        ratioSeries = EmptySeriesData()
        maxFromMaxValueFound = Double.MIN_VALUE
        maxFromMinValueFound = Double.MIN_VALUE
        dsMaxSeries = EmptySeriesData()
        dsMinSeries = EmptySeriesData()
        maxTreatmentsValue = 0.0
        treatmentsSeries = EmptySeriesData()
        maxEpsValue = 0.0
        epsSeries = EmptySeriesData()
        maxTherapyEventValue = 0.0
        therapyEventSeries = EmptySeriesData()
        heartRateGraphSeries = EmptySeriesData()
        stepsCountGraphSeries = EmptySeriesData()
        maxVarSensValueFound = 200.0
        minVarSensValueFound = 50.0
        varSensSeries = EmptySeriesData()
    }

    override fun initRange() {
        toTime = initialToTime()
        fromTime = toTime - T.hours(rangeToDisplay.toLong()).msecs()
        endTime = toTime
    }

    override fun temporaryBasalText(): String = ""
    override fun temporaryBasalDialogText(): String = ""
    @DrawableRes override fun temporaryBasalIcon(): Int = 0
    @AttrRes override fun temporaryBasalColor(context: Context?): Int = 0
    override fun extendedBolusText(): String = ""
    override fun extendedBolusDialogText(): String = ""

    private fun initialToTime(): Long {
        val tz = TimeZone.currentSystemDefault()
        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        val local = now.toLocalDateTime(tz)
        val truncatedHour = LocalDateTime(local.year, local.month, local.day, local.hour, 0)
        val nextFullHour = truncatedHour.toInstant(tz).plus(1, DateTimeUnit.HOUR, tz)
        return nextFullHour.toEpochMilliseconds() + 100000
    }
}
