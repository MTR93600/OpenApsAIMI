package app.aaps.ui.compose.dashboard

import app.aaps.core.interfaces.overview.graph.BgDataPoint
import app.aaps.core.interfaces.overview.graph.BgRange
import app.aaps.core.interfaces.overview.graph.BgType
import app.aaps.core.interfaces.overview.graph.BolusGraphPoint
import app.aaps.core.interfaces.overview.graph.BolusType
import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import app.aaps.core.interfaces.overview.graph.TreatmentGraphData
import kotlin.test.Test
import kotlin.test.assertEquals

class DashboardGraphSnapshotMapperTest {

    @Test
    fun mapsOverviewGlucoseAndPredictionValuesWithoutChangingUnitsOrTimestamps() {
        val readings = listOf(
            BgDataPoint(1_000L, 5.8, BgRange.IN_RANGE, BgType.REGULAR),
            BgDataPoint(2_000L, 6.1, BgRange.IN_RANGE, BgType.BUCKETED),
        )
        val predictions = listOf(
            BgDataPoint(3_000L, 6.4, BgRange.IN_RANGE, BgType.IOB_PREDICTION),
            BgDataPoint(3_000L, 7.1, BgRange.IN_RANGE, BgType.UAM_PREDICTION),
        )

        val snapshot = DashboardGraphSnapshotMapper.create(
            readings = readings,
            predictions = predictions,
            showPredictions = true,
            rangeStartEpochMs = 1_000L,
            rangeEndEpochMs = 3_000L,
            nowEpochMs = 2_000L,
            targetLow = 4.0,
            targetHigh = 10.0,
        )

        assertEquals(listOf(1_000L to 5.8, 2_000L to 6.1), snapshot.points.map { it.timestampEpochMs to it.value })
        assertEquals(
            listOf(3_000L to 6.4, 3_000L to 7.1),
            snapshot.predictionPoints.map { it.timestampEpochMs to it.value },
        )
        assertEquals(
            listOf(BgType.IOB_PREDICTION, BgType.UAM_PREDICTION),
            snapshot.predictionPoints.map { it.type },
        )
        assertEquals(4.0, snapshot.targetLowMgdl)
        assertEquals(10.0, snapshot.targetHighMgdl)
    }

    @Test
    fun hidesPredictionsWhenOverviewGraphConfigurationDisablesThem() {
        val predictions = listOf(BgDataPoint(3_000L, 6.4, BgRange.IN_RANGE, BgType.IOB_PREDICTION))

        val snapshot = DashboardGraphSnapshotMapper.create(
            readings = emptyList(),
            predictions = predictions,
            showPredictions = false,
            rangeStartEpochMs = 1_000L,
            rangeEndEpochMs = 3_000L,
            nowEpochMs = 2_000L,
            targetLow = null,
            targetHigh = null,
        )

        assertEquals(emptyList(), snapshot.predictionPoints)
    }

    @Test
    fun mapsOnlyValidSmbBolusesFromTheOverviewTreatmentCache() {
        val treatments = TreatmentGraphData(
            boluses = listOf(
                BolusGraphPoint(1_500L, 0.2, BolusType.SMB, isValid = true, label = "0.20 U"),
                BolusGraphPoint(1_700L, 0.5, BolusType.NORMAL, isValid = true, label = "0.50 U"),
                BolusGraphPoint(1_900L, 0.2, BolusType.SMB, isValid = false, label = "0.20 U"),
            ),
            carbs = emptyList(),
            extendedBoluses = emptyList(),
            therapyEvents = emptyList(),
        )

        val snapshot = DashboardGraphSnapshotMapper.create(
            readings = emptyList(),
            predictions = emptyList(),
            treatments = treatments,
            showPredictions = false,
            rangeStartEpochMs = 1_000L,
            rangeEndEpochMs = 2_000L,
            nowEpochMs = 2_000L,
            targetLow = null,
            targetHigh = null,
        )

        assertEquals(listOf(1_500L), snapshot.smbMarkers.map { it.timestampEpochMs })
    }

    @Test
    fun neverLabelsActualBasalStepsAsTemporaryBasalIntervalsOrChanges() {
        val basalSteps = listOf(
            GraphDataPoint(0L, 1.0),
            GraphDataPoint(60_000L, 0.5),
            GraphDataPoint(120_000L, 0.5),
        )

        val snapshot = DashboardGraphSnapshotMapper.create(
            readings = emptyList(),
            predictions = emptyList(),
            actualBasalSteps = basalSteps,
            showPredictions = false,
            rangeStartEpochMs = 30_000L,
            rangeEndEpochMs = 90_000L,
            nowEpochMs = 90_000L,
            targetLow = null,
            targetHigh = null,
        )

        assertEquals(emptyList(), snapshot.tbrSegments)
        assertEquals(emptyList(), snapshot.tbrMarkerEpochMs)
    }
}
