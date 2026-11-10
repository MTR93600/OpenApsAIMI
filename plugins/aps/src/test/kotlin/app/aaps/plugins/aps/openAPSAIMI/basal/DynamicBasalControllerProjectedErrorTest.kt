package app.aaps.plugins.aps.openAPSAIMI.basal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Caractérise la refonte « erreur projetée » (lot 1) du [DynamicBasalController].
 *
 * Rappel du défaut corrigé : le couple (P, D) historique pesait `0,05` par mg/dL d'écart contre
 * `12 × 0,15 = 1,8` par mg/dL/5min de pente, soit **36:1**. Une montée de +2,5 mg/dL/5min ajoutait +4,5 au
 * multiplicateur, qu'il aurait fallu compenser par une glycémie 90 mg/dL sous la cible.
 */
class DynamicBasalControllerProjectedErrorTest {

    private val horizon = DynamicBasalController.PROJECTION_HORIZON_MIN

    private fun input(
        bg: Double,
        target: Double,
        delta: Double,
        shortAvgDelta: Double = delta,
        profileBasal: Double = 0.6,
        horizonMin: Double? = null,
    ) = DynamicBasalController.Input(
        bg = bg,
        targetBg = target,
        delta = delta,
        shortAvgDelta = shortAvgDelta,
        longAvgDelta = shortAvgDelta,
        iob = 0.0,
        maxIob = 20.0,
        profileBasal = profileBasal,
        variableSensitivity = 50.0,
        duraISFminutes = 0.0,
        predictedBgOverride = null,
        mode = DynamicBasalController.Mode.STANDARD,
        projectionHorizonMin = horizonMin,
    )

    @Test fun legacyGainRatioIs36To1() {
        // Verrouille le constat qui motive le lot 1, sur l'ancienne formulation.
        val pGainPerMgdl = 0.05
        val dGainPerMgdlPer5min = 12.0 * 0.15
        assertEquals(36.0, dGainPerMgdlPer5min / pGainPerMgdl, 1e-9)
    }

    @Test fun prod20260802_0836_legacyAsksFiveTimesProfileWhileBelowTarget() {
        // Tick réel : BG 104, cible 115 (donc 11 mg/dL SOUS la cible), pente +2,54 → Mult 5,03x observé.
        val legacy = DynamicBasalController.compute(input(bg = 104.0, target = 115.0, delta = 2.54))
        val mult = legacy.rate / 0.6
        assertEquals(5.02, mult, 0.02)
        assertTrue(legacy.reason.startsWith("PI-Fallback"))
    }

    @Test fun prod20260802_0836_projectedIsFarMoreConservative() {
        val projected = DynamicBasalController.compute(
            input(bg = 104.0, target = 115.0, delta = 2.54, horizonMin = horizon)
        )
        // Projection : 104 + 2,54 × (60/5) = 134,5 → écart +19,5 → mult = 1 + 19,5 × 0,05 ≈ 1,97
        val mult = projected.rate / 0.6
        assertEquals(1.97, mult, 0.02)
        assertTrue(projected.reason.startsWith("PI-Projected"))
        assertTrue(projected.reason.contains("H=60min"))
    }

    @Test fun prod20260802_0841_legacyAsksAlmostEightTimesProfile() {
        val legacy = DynamicBasalController.compute(input(bg = 109.2, target = 115.0, delta = 4.0))
        assertEquals(7.91, legacy.rate / 0.6, 0.02)
        val projected = DynamicBasalController.compute(
            input(bg = 109.2, target = 115.0, delta = 4.0, horizonMin = horizon)
        )
        // 109,2 + 4,0 × 12 = 157,2 → écart +42,2 → mult ≈ 3,11
        assertEquals(3.11, projected.rate / 0.6, 0.02)
    }

    @Test fun steadyGlucose_isUnchangedByTheRefactor() {
        // Sans pente, les deux formulations coïncident : la refonte ne touche que l'influence du trend.
        val legacy = DynamicBasalController.compute(input(bg = 200.0, target = 100.0, delta = 0.0))
        val projected = DynamicBasalController.compute(
            input(bg = 200.0, target = 100.0, delta = 0.0, horizonMin = horizon)
        )
        assertEquals(legacy.rate, projected.rate, 1e-9)
    }

    @Test fun genuineHyper_stillCorrects() {
        // Le lot 1 ne doit pas rendre le moteur inerte sur une vraie hyper montante.
        val projected = DynamicBasalController.compute(
            input(bg = 250.0, target = 100.0, delta = 3.0, horizonMin = horizon)
        )
        assertTrue(projected.rate / 0.6 > 5.0, "hyper montante doit rester corrigée, obtenu ${projected.rate / 0.6}")
    }

    @Test fun projectedNeverExceedsLegacy_overTheWholeDomainIncludingFalls() {
        // Invariant de sécurité du lot 1, vérifié en montée ET en descente.
        //
        // En descente, la projection seule freinerait MOINS que l'ancienne formulation (gain dérivé 0,6
        // contre 1,8) : sur une hyper qui redescend vite, l'ancien signal tombait à 0 là où le projeté
        // demanderait encore ~1,8× le profil. Le contrôleur retient donc le minimum des deux.
        // Régression détectée par le harnais de rejeu (lot 5) sur 10 ticks réels, pas par ce test —
        // qui ne couvrait initialement que d >= 0.
        var bg = 80.0
        while (bg <= 260.0) {
            var d = -6.0
            while (d <= 6.0) {
                val legacy = DynamicBasalController.compute(input(bg = bg, target = 110.0, delta = d))
                val projected = DynamicBasalController.compute(
                    input(bg = bg, target = 110.0, delta = d, horizonMin = horizon)
                )
                assertTrue(
                    projected.rate <= legacy.rate + 1e-9,
                    "bg=$bg delta=$d : projeté ${projected.rate} > legacy ${legacy.rate}"
                )
                d += 0.5
            }
            bg += 20.0
        }
    }

    @Test fun fallingBelowTarget_stillBrakesToZero() {
        val projected = DynamicBasalController.compute(
            input(bg = 95.0, target = 110.0, delta = -2.0, horizonMin = horizon)
        )
        assertEquals(0.0, projected.rate)
        assertTrue(projected.reason.contains("Brake"))
    }
}
