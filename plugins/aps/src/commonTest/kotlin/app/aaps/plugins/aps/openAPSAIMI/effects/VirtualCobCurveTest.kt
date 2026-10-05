package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.physio.HealthContextSnapshot
import app.aaps.plugins.aps.openAPSAIMI.pkpd.AdvancedPredictionCurves
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdSoftFloorPathMin
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdSoftFloorTelemetry
import app.aaps.plugins.aps.openAPSAIMI.tpo.InMemoryAimiStorage
import app.aaps.plugins.aps.openAPSAIMI.tpo.JsonBackedPreferences
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Preference on, the same 9 g inputs as the estimator, and the heart-rate gate at 0 g.
 * Both feed [decideApplyAdvancedPredictions]. The pkpd flags are the shell probe's unset
 * values (false), not the key defaults.
 */
class VirtualCobCurveTest {

    @Test
    fun nineGramsAndHeartRateGateFeedTheSameCurve() {
        val nine = publish(HealthContextSnapshot())
        val gated = publish(HealthContextSnapshot(hrNow = 110, rhrResting = 60))
        assertEquals(9.0, nine.grams, 1e-9)
        assertEquals(NINE_GRAM_CURVE, nine.lines.joinToString("\n"))
        assertEquals(198.0, nine.eventual, 1e-9)
        assertEquals(0.0, gated.grams, 1e-9)
        assertEquals(HEART_RATE_GATE_CURVE, gated.lines.joinToString("\n"))
        assertEquals(170.0, gated.eventual, 1e-9)
    }

    private fun publish(snapshot: HealthContextSnapshot): Curve {
        val storage = InMemoryAimiStorage()
        val prefs = JsonBackedPreferences(storage)
        prefs.put(BooleanKey.OApsAIMIUndeclaredCobEnabled, true)
        prefs.put(DoubleKey.OApsAIMIweight, 70.0)
        prefs.put(DoubleKey.OApsAIMIUndeclaredCobMaxG, 25.0)
        prefs.put(BooleanKey.OApsAIMIPkpdEndogenousReversion, false)
        prefs.put(BooleanKey.OApsAIMIPkpdHyperReversion, false)
        prefs.put(BooleanKey.OApsAIMIPkpdStackAwareGuardB, false)
        prefs.put(BooleanKey.OApsAIMIT3cCfrdMode, false)
        val log = mutableListOf<String>()
        val profile = virtualCobCurveProfile(carbRatio = 10.0)
        val meal = MealData(mealCOB = 0.0).also { it.slopeFromMinDeviation = 2.0 }
        val rT = RT(runningDynamicIsf = false)
        var grams = -1.0
        decideApplyAdvancedPredictions(
            bg = 150.0,
            delta = 3.0f,
            sens = 40.0,
            iobDataArray = arrayOf(IobTotal(time = aimiWallClockMs(), iob = 0.0, activity = 0.0)),
            mealData = meal,
            profile = profile,
            rT = rT,
            preferences = prefs,
            consoleLog = log,
            calls = object : AimiAdvancedPredictionCalls {
                override fun nowMs() = aimiWallClockMs()
                override fun virtualCob(
                    bg: Double,
                    delta: Float,
                    sens: Double,
                    profile: OapsProfileAimi,
                    mealData: MealData,
                    declaredOrAdvisorCob: Double,
                ): Double {
                    grams = decideEstimateUndeclaredVirtualCob(
                        enabled = prefs.get(BooleanKey.OApsAIMIUndeclaredCobEnabled),
                        declaredOrAdvisorCob = declaredOrAdvisorCob,
                        consoleLog = log,
                    ) {
                        undeclaredVirtualCobInput(
                            snapshot = snapshot,
                            estimatedRaMgdlPerMin = 2.0,
                            isfMgdlPerU = sens,
                            carbRatioGPerU = profile.carb_ratio,
                            bgMgdl = bg,
                            deltaMgdl5m = delta.toDouble(),
                            slopeFromMinDeviation = mealData.slopeFromMinDeviation,
                            patientWeightKg = prefs.get(DoubleKey.OApsAIMIweight),
                            tdd24hU = 40.0,
                            activityContextActive = false,
                            mealProb = 0.8,
                            falseMealSuppression = false,
                            exerciseLockoutActive = false,
                            postHypoActive = false,
                            cfrdExacerbationActive =
                                prefs.get(BooleanKey.OApsAIMIT3cCfrdMode) &&
                                    prefs.get(BooleanKey.OApsAIMIT3cCfrdExacerbationMode),
                            maxGramsPref = prefs.get(DoubleKey.OApsAIMIUndeclaredCobMaxG),
                        )
                    }
                    return grams
                }

                override fun recordSoftFloor(curves: AdvancedPredictionCurves): PkpdSoftFloorTelemetry =
                    decideRecordPkpdSoftFloor(
                        curves = curves,
                        endogenousReversionEnabled = prefs.get(BooleanKey.OApsAIMIPkpdEndogenousReversion),
                        calls = object : AimiPkpdSoftFloorWrite {
                            override fun writeTelemetryAndLog(telemetry: PkpdSoftFloorTelemetry) {
                                log += PkpdSoftFloorPathMin.formatLogLine(telemetry)
                            }
                        },
                    )

                override fun writeCurves(curves: AdvancedPredictionCurves) = Unit
                override fun writePredictionSize(size: Int) = Unit
                override fun writePredictionAvailable(available: Boolean) = Unit
                override fun writeEventualSnapshot(value: Double) = Unit
                override fun writePredictedBg(value: Float) = Unit
                override fun logError(message: String) = Unit
            },
        )
        return Curve(grams, log, rT.eventualBG ?: -1.0)
    }

    private data class Curve(val grams: Double, val lines: List<String>, val eventual: Double)

    private companion object {
        val NINE_GRAM_CURVE = """
            🍽️ VIRTUAL_COB: g=9.0 raw=11.3 cap=25.0 gated=false reason=ra_meal_estimate
            PKPD_SOFT_FLOOR: raw=150 soft=150 hybT=198 hitFloor=false applied=false endo=false fallSuppressed=false reason=endo_reversion_disabled
            PRED_SET size=49 eventual=198 min=150 uamT=163 source=AdvancedCurves
            Prédiction avancée avec ISF final de 40.0 (Avancé)
        """.trimIndent()

        val HEART_RATE_GATE_CURVE = """
            🍽️ VIRTUAL_COB: g=0.0 raw=0.0 cap=0.0 gated=true reason=hr_inflammation
            PKPD_SOFT_FLOOR: raw=150 soft=150 hybT=169 hitFloor=false applied=false endo=false fallSuppressed=false reason=endo_reversion_disabled
            PRED_SET size=49 eventual=170 min=150 uamT=170 source=AdvancedCurves
            Prédiction avancée avec ISF final de 40.0 (Avancé)
        """.trimIndent()
    }
}
