package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.plugins.aps.openAPSAIMI.patient.PatientStateRuntimeRepository
import app.aaps.plugins.aps.openAPSAIMI.physio.HealthContextSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The low-prediction scene of `lowPredictionRequestsAQuarterBasal`.
 * The shell field `bg` is still 0 when the runtime runs; the glucose status 100 is not that field.
 * The scenario path minimum is the locked floor, 39 mg/dL.
 */
class DecideRefreshPatientStateRuntimeTest {

    @Test
    fun lowPredictionRequestsTheAndroidTreeAndMealLines() {
        try {
            val lines = lowPredictionPatientLog(
                snapshot = HealthContextSnapshot(),
                nowMs = 1_700_000_000_000L,
            )
            assertEquals(
                listOf(
                    "TREE_DEPLOYED trunk=SENSOR_UNCERTAIN conf=0.90 risk=CRITICAL kinetics=NO_STAGE",
                    "Tree: sensor uncertain | conf 90% | risk critical | sensor uncertain",
                    "MEAL_CERTAINTY level=NONE tree=NONE rise=WEAK terminals=HYPO_CONFLICT effortVeto=false",
                    "Harmonia sim: blocked SENSOR_UNCERTAIN | sensor_uncertain,low_or_falling_bg,max_iob_pressure,critical_risk",
                ),
                lines,
            )
        } finally {
            PatientStateRuntimeRepository.clear()
        }
    }
}
