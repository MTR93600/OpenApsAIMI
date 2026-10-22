package app.aaps.plugins.aps.openAPSAIMI.dashboard

import app.aaps.core.interfaces.dashboard.AimiGlassDashboardData
import app.aaps.plugins.aps.openAPSAIMI.learning.BasalMlDashboardSnapshot
import app.aaps.plugins.aps.openAPSAIMI.ml.AimiSmbDashboardSnapshot
import app.aaps.plugins.aps.openAPSAIMI.ml.TrainingOutcome
import app.aaps.plugins.aps.openAPSAIMI.ml.TrainingResult
import app.aaps.plugins.aps.openAPSAIMI.patient.PhysioLiveDigest
import app.aaps.plugins.aps.openAPSAIMI.pkpd.AdvancedPredictionCurves
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AimiGlassDashboardSnapshotTest {

    @Test
    fun exposesOnlyFinitePublishedTrajectoryTerminals() {
        val snapshot = AimiGlassDashboardSnapshot.from(
            curves = AdvancedPredictionCurves(
                iob = listOf(122.0, 115.5),
                cob = listOf(122.0, Double.NaN),
                uam = emptyList(),
                zt = emptyList(),
                hybrid = listOf(122.0, 130.25),
            ),
            physio = null,
        )

        assertEquals(130.25, snapshot.hybridTerminalMgdl)
        assertEquals(115.5, snapshot.iobTerminalMgdl)
        assertNull(snapshot.cobTerminalMgdl)
        assertNull(snapshot.uamTerminalMgdl)
        assertNull(snapshot.physio)
    }

    @Test
    fun preservesRealPhysioDigestAndDoesNotReplaceMissingSignalsWithZeroData() {
        val digest = PhysioLiveDigest(
            stepsLast15m = 17,
            hrNowBpm = 72,
            source = "Health Connect",
            confidence = 0.8,
        )

        val snapshot = AimiGlassDashboardSnapshot.from(curves = null, physio = digest)

        assertNull(snapshot.hybridTerminalMgdl)
        assertNull(snapshot.iobTerminalMgdl)
        assertNull(snapshot.cobTerminalMgdl)
        assertNull(snapshot.uamTerminalMgdl)
        assertEquals(digest, snapshot.physio)
    }

    @Test
    fun sharedDashboardProjectionPreservesPublishedTrajectoryAndPhysioValues() {
        val digest = PhysioLiveDigest(
            stepsLast15m = 17,
            stepsLast60m = 83,
            hrNowBpm = 72,
            hrAvg15mBpm = 69,
            rhrRestingBpm = 55,
            hrvRmssd = 31.5,
            activityState = "WALKING",
            sleepDebtMinutes = 24,
            source = "Health Connect",
            confidence = 0.8,
            thermalHypothesis = "RECOVERY",
            thermalDeltaVsBaselineC = 0.3,
            thermalInflammationIndex = 0.2,
            thermalNarrative = "Thermal recovery",
        )
        val snapshot = AimiGlassDashboardSnapshot.from(
            curves = AdvancedPredictionCurves(
                iob = listOf(110.0, 103.0),
                cob = listOf(110.0, 117.0),
                uam = listOf(110.0, 120.0),
                zt = emptyList(),
                hybrid = listOf(110.0, 126.0),
            ),
            physio = digest,
        )

        val shared = snapshot.toSharedData(
            physioPresentation = AimiGlassDashboardSnapshot.PhysioPresentation(
                modeHeadline = "Mode (80%)",
                intentSummary = "Dominant EXERCISE · 80% confidence across 1 context",
                thermalSummary = "Thermal recovery (+0.3°C vs baseline)",
                updatedSummary = "Updated just now",
            ),
        )

        assertEquals(126.0, shared.trajectory?.hybridTerminalMgdl)
        assertEquals(103.0, shared.trajectory?.iobTerminalMgdl)
        assertEquals(117.0, shared.trajectory?.cobTerminalMgdl)
        assertEquals(120.0, shared.trajectory?.uamTerminalMgdl)
        assertEquals(17, shared.physio?.stepsLast15m)
        assertEquals(83, shared.physio?.stepsLast60m)
        assertEquals(72, shared.physio?.hrNowBpm)
        assertEquals(69, shared.physio?.hrAvg15mBpm)
        assertEquals(55, shared.physio?.rhrRestingBpm)
        assertEquals(31.5, shared.physio?.hrvRmssd)
        assertEquals("WALKING", shared.physio?.activityState)
        assertEquals("Health Connect", shared.physio?.source)
        assertEquals(0.8, shared.physio?.confidence)
        assertEquals("RECOVERY", shared.physio?.thermalHypothesis)
        assertEquals(0.3, shared.physio?.thermalDeltaVsBaselineC)
        assertEquals(0.2, shared.physio?.thermalInflammationIndex)
        assertEquals("Thermal recovery", shared.physio?.thermalNarrative)
        assertEquals("Mode (80%)", shared.physio?.presentation?.modeHeadline)
        assertEquals(
            "Dominant EXERCISE · 80% confidence across 1 context",
            shared.physio?.presentation?.intentSummary,
        )
        assertEquals("Thermal recovery (+0.3°C vs baseline)", shared.physio?.presentation?.thermalSummary)
        assertEquals("Updated just now", shared.physio?.presentation?.updatedSummary)
    }

    @Test
    fun dashboardProjectionPreservesBasalAndSmbTrainingAndModelFacts() {
        val smb = AimiSmbDashboardSnapshot(
            lastAttempt = TrainingResult(
                atMs = 1_700_000_000_000L,
                outcome = TrainingOutcome.REJECTED_BY_GATES,
                totalRows = 520,
                samplesAfterFilter = 310,
                rowsRejectedByFilter = 210,
                gateDetail = "spread below required threshold",
            ),
            waiting = null,
            lastAttemptAtMs = 1_700_000_000_000L,
            lastTrainedAtMs = 1_699_000_000_000L,
            circuitOpen = false,
            modelLoaded = true,
            modelFileExists = true,
            modelFileModifiedAtMs = 1_699_000_000_000L,
            modelFileSizeBytes = 14_200L,
        )
        val basal = BasalMlDashboardSnapshot(
            lastTrainedAtMs = 1_698_000_000_000L,
            sampleCount = 245,
            circuitOpen = true,
            modelFileExists = true,
            modelFileModifiedAtMs = 1_698_000_000_000L,
            modelFileSizeBytes = 5_600L,
        )

        val shared = AimiGlassDashboardSnapshot.from(null, null)
            .toSharedData(
                basalTraining = basal,
                smbTraining = smb,
                keyFactors = AimiGlassDashboardData.KeyFactors(
                    targetBgMgdl = 110.0,
                    maxIobUnits = 3.0,
                    maxSmbUnits = 1.2,
                    isfMgdlPerUnit = 45.0,
                    stableBgMinutes = 36.0,
                ),
                safety = AimiGlassDashboardData.Safety(
                    tdd7DaysPerHour = 0.75,
                    lowTirLastHourPercent = 1.0,
                    lowTirTodayPercent = 2.0,
                    stepsLast5Minutes = 42,
                ),
            )

        assertEquals(245, shared.training?.basal?.sampleCount)
        assertEquals(true, shared.training?.basal?.circuitOpen)
        assertEquals(1_698_000_000_000L, shared.training?.basal?.modelFile?.modifiedAtMs)
        assertEquals(5_600L, shared.training?.basal?.modelFile?.sizeBytes)
        assertEquals(AimiGlassDashboardData.SmbOutcome.REJECTED_BY_GATES, shared.training?.smb?.lastAttempt?.outcome)
        assertEquals(310, shared.training?.smb?.lastAttempt?.samplesAfterFilter)
        assertEquals(210L, shared.training?.smb?.lastAttempt?.rowsRejectedByFilter)
        assertEquals("spread below required threshold", shared.training?.smb?.lastAttempt?.detail)
        assertEquals(true, shared.training?.smb?.modelLoaded)
        assertEquals(1_699_000_000_000L, shared.training?.smb?.modelFile?.modifiedAtMs)
        assertEquals(14_200L, shared.training?.smb?.modelFile?.sizeBytes)
        assertEquals(110.0, shared.keyFactors?.targetBgMgdl)
        assertEquals(3.0, shared.keyFactors?.maxIobUnits)
        assertEquals(1.2, shared.keyFactors?.maxSmbUnits)
        assertEquals(45.0, shared.keyFactors?.isfMgdlPerUnit)
        assertEquals(36.0, shared.keyFactors?.stableBgMinutes)
        assertEquals(0.75, shared.safety?.tdd7DaysPerHour)
        assertEquals(1.0, shared.safety?.lowTirLastHourPercent)
        assertEquals(2.0, shared.safety?.lowTirTodayPercent)
        assertEquals(42, shared.safety?.stepsLast5Minutes)
    }

    @Test
    fun trajectoryPublicationOlderThanExistingNineMinuteBgFreshnessWindowIsMarkedStale() {
        val curves = AdvancedPredictionCurves(
            iob = listOf(120.0, 110.0),
            cob = listOf(120.0, 130.0),
            uam = emptyList(),
            zt = emptyList(),
            hybrid = listOf(120.0, 125.0),
        )

        val atNineMinutes = AimiGlassDashboardSnapshot.from(
            curves = curves,
            physio = null,
            trajectoryPublishedAtMs = 1_000_000L,
            nowEpochMs = 1_000_000L + 9L * 60_000L,
        ).toSharedData()
        val beyondNineMinutes = AimiGlassDashboardSnapshot.from(
            curves = curves,
            physio = null,
            trajectoryPublishedAtMs = 1_000_000L,
            nowEpochMs = 1_000_000L + 9L * 60_000L + 1L,
        ).toSharedData()
        val missingPublicationTime = AimiGlassDashboardSnapshot.from(
            curves = curves,
            physio = null,
            trajectoryPublishedAtMs = null,
            nowEpochMs = 1_000_000L,
        ).toSharedData()

        assertFalse(atNineMinutes.trajectory!!.isStale)
        assertTrue(beyondNineMinutes.trajectory!!.isStale)
        assertTrue(missingPublicationTime.trajectory!!.isStale)
    }
}
