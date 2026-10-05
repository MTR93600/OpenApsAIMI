package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.plugins.aps.openAPSAIMI.UndeclaredCobEstimator
import app.aaps.plugins.aps.openAPSAIMI.physio.HealthContextSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DecideUndeclaredVirtualCobTest {

    @Test
    fun preferenceOffOrDeclaredCarbsReturnZeroAndDoNotLog() {
        val off = mutableListOf<String>()
        assertEquals(
            0.0,
            decideEstimateUndeclaredVirtualCob(
                enabled = false,
                declaredOrAdvisorCob = 0.0,
                consoleLog = off,
            ) { error("snapshot read while the preference is off") },
        )
        assertTrue(off.isEmpty())

        val declared = mutableListOf<String>()
        assertEquals(
            0.0,
            decideEstimateUndeclaredVirtualCob(
                enabled = true,
                declaredOrAdvisorCob = 36.0,
                consoleLog = declared,
            ) { error("snapshot read while carbs are already declared") },
        )
        assertTrue(declared.isEmpty())
    }

    @Test
    fun raMealEstimateLogsNineGramsFromTheSnapshotAndTheCap() {
        val log = mutableListOf<String>()
        val grams = decideEstimateUndeclaredVirtualCob(
            enabled = true,
            declaredOrAdvisorCob = 0.0,
            consoleLog = log,
        ) {
            undeclaredVirtualCobInput(
                snapshot = HealthContextSnapshot(),
                estimatedRaMgdlPerMin = 2.0,
                isfMgdlPerU = 40.0,
                carbRatioGPerU = 10.0,
                bgMgdl = 150.0,
                deltaMgdl5m = 3.0,
                slopeFromMinDeviation = 2.0,
                patientWeightKg = 70.0,
                tdd24hU = 40.0,
                activityContextActive = false,
                mealProb = 0.8,
                falseMealSuppression = false,
                exerciseLockoutActive = false,
                postHypoActive = false,
                cfrdExacerbationActive = false,
                maxGramsPref = 25.0,
            )
        }
        assertEquals(9.0, grams)
        assertEquals(
            listOf("🍽️ VIRTUAL_COB: g=9.0 raw=11.3 cap=25.0 gated=false reason=ra_meal_estimate"),
            log,
        )
    }

    @Test
    fun elevatedHeartRateOnTheSnapshotGatesBeforeTheMealEstimate() {
        val log = mutableListOf<String>()
        val grams = decideEstimateUndeclaredVirtualCob(
            enabled = true,
            declaredOrAdvisorCob = 0.0,
            consoleLog = log,
        ) {
            undeclaredVirtualCobInput(
                snapshot = HealthContextSnapshot(hrNow = 110, rhrResting = 60),
                estimatedRaMgdlPerMin = 2.0,
                isfMgdlPerU = 40.0,
                carbRatioGPerU = 10.0,
                bgMgdl = 150.0,
                deltaMgdl5m = 3.0,
                slopeFromMinDeviation = 2.0,
                patientWeightKg = 70.0,
                tdd24hU = 40.0,
                activityContextActive = false,
                mealProb = 0.8,
                falseMealSuppression = false,
                exerciseLockoutActive = false,
                postHypoActive = false,
                cfrdExacerbationActive = false,
                maxGramsPref = 25.0,
            )
        }
        assertEquals(0.0, grams)
        assertEquals(
            listOf("🍽️ VIRTUAL_COB: g=0.0 raw=0.0 cap=0.0 gated=true reason=hr_inflammation"),
            log,
        )
    }
}
