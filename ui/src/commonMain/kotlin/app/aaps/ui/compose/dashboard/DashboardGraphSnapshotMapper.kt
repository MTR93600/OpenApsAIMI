package app.aaps.ui.compose.dashboard

import app.aaps.core.interfaces.overview.graph.BgDataPoint
import app.aaps.core.interfaces.overview.graph.BolusType
import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import app.aaps.core.interfaces.overview.graph.TreatmentGraphData
import app.aaps.core.ui.compose.dashboard.DashboardGraphPoint
import app.aaps.core.ui.compose.dashboard.DashboardGraphRenderInput
import app.aaps.core.ui.compose.dashboard.DashboardSmbMarker
import app.aaps.core.ui.compose.dashboard.DashboardTbrSegment

/**
 * Maps the existing Overview graph cache into the shared dashboard renderer's immutable snapshot.
 *
 * Overview values and target marks are already expressed in the user's selected glucose units.
 * This mapper deliberately preserves them unchanged; no conversion or alternate clinical data path
 * is introduced by selecting the dashboard.
 */
internal object DashboardGraphSnapshotMapper {

    fun create(
        readings: List<BgDataPoint>,
        predictions: List<BgDataPoint>,
        treatments: TreatmentGraphData? = null,
        /**
         * Legacy cache data is scheduled/actual basal, not a temporary-basal history. Retained for
         * callers/tests to prove it is not misrepresented as TBR; intentionally ignored until the
         * source exposes true temporary-basal intervals.
         */
        actualBasalSteps: List<GraphDataPoint> = emptyList(),
        showPredictions: Boolean,
        rangeStartEpochMs: Long,
        rangeEndEpochMs: Long,
        nowEpochMs: Long,
        targetLow: Double?,
        targetHigh: Double?,
    ): DashboardGraphRenderInput {
        return DashboardGraphRenderInput(
            fromTimeEpochMs = rangeStartEpochMs,
            toTimeEpochMs = rangeEndEpochMs,
            nowEpochMs = nowEpochMs,
            targetLowMgdl = targetLow,
            targetHighMgdl = targetHigh,
            points = readings.map { DashboardGraphPoint(it.timestamp, it.value, it.type) },
            predictionPoints = if (showPredictions) {
                predictions.map { DashboardGraphPoint(it.timestamp, it.value, it.type) }
            } else {
                emptyList()
            },
            smbMarkers = treatments?.boluses
                ?.asSequence()
                ?.filter { it.isValid && it.bolusType == BolusType.SMB }
                ?.map { DashboardSmbMarker(it.timestamp) }
                ?.toList()
                .orEmpty(),
            tbrMarkerEpochMs = emptyList(),
            tbrSegments = emptyList(),
        )
    }
}
