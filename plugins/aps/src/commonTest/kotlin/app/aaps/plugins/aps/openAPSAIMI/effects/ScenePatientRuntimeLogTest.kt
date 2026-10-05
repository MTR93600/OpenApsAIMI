package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.physio.HealthContextSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals

class ScenePatientRuntimeLogTest {

    private val RISING_SCENE_RUNTIME = """
        TREE_DEPLOYED trunk=SENSOR_UNCERTAIN conf=0.90 risk=CRITICAL kinetics=NO_STAGE
        Tree: sensor uncertain | conf 90% | risk critical | sensor uncertain
        MEAL_CERTAINTY level=NONE tree=NONE rise=OK terminals=UNKNOWN effortVeto=false
        Harmonia sim: blocked SENSOR_UNCERTAIN | sensor_uncertain,max_iob_pressure,critical_risk
    """.trimIndent()

    private val NIGHT_SCENE_RUNTIME = """
        TREE_DEPLOYED trunk=SENSOR_UNCERTAIN conf=0.90 risk=CRITICAL kinetics=NO_STAGE
        Tree: sensor uncertain | conf 90% | risk critical | sensor uncertain
        MEAL_CERTAINTY level=NONE tree=NONE rise=WEAK terminals=UNKNOWN effortVeto=false
        Harmonia sim: blocked SENSOR_UNCERTAIN | sensor_uncertain,max_iob_pressure,critical_risk
    """.trimIndent()

    @Test
    fun mealSportAndNightPrintTheRuntimeLines() {
        val now = aimiWallClockMs()
        val meal = scenePatientLog(HealthContextSnapshot(), now, bgMgdl = 160.0, deltaMgdl = 2.0)
        val sport = scenePatientLog(HealthContextSnapshot(), now, bgMgdl = 180.0, deltaMgdl = 5.0)
        val night = scenePatientLog(HealthContextSnapshot(), now, bgMgdl = 180.0, deltaMgdl = 0.0)
        assertEquals(RISING_SCENE_RUNTIME, meal.joinToString("\n"))
        assertEquals(RISING_SCENE_RUNTIME, sport.joinToString("\n"))
        assertEquals(NIGHT_SCENE_RUNTIME, night.joinToString("\n"))
    }
}
