package app.aaps.plugins.aps.openAPSAIMI.basal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Caractérise les deux garde-fous du lot 3. Les cas nommés `prod…` rejouent des ticks réels observés sur
 * le build `4.0.0.0-dev.AIMI.310726` (paquet de support du 2026-08-02), où le canal basal automatique a
 * demandé 4 à 5 U/h sur un profil de 0,5–0,6 U/h.
 */
class BasalChannelSafetyGuardsTest {

    // ---------------------------------------------------------------- smbZeroedBySafety

    @Test fun smbNotZeroed_isNotASafetyHold() {
        assertFalse(BasalChannelSafetyGuards.smbZeroedBySafety(criticalSafetyZeroed = false, contextSuppressSmb = false))
    }

    @Test fun criticalSafetyZeroing_isASafetyHold() {
        assertTrue(BasalChannelSafetyGuards.smbZeroedBySafety(criticalSafetyZeroed = true, contextSuppressSmb = false))
    }

    @Test fun hypoRecoveryContextSuppression_isASafetyHold() {
        assertTrue(BasalChannelSafetyGuards.smbZeroedBySafety(criticalSafetyZeroed = false, contextSuppressSmb = true))
    }

    // ---------------------------------------------------------------- shouldBlockBasalFirst

    @Test fun guardsOff_neverBlocks_soLegacyBehaviourIsPreserved() {
        assertFalse(
            BasalChannelSafetyGuards.shouldBlockBasalFirst(
                guardsEnabled = false, criticalSafetyZeroed = true, contextSuppressSmb = true, mealModeActive = false
            )
        )
    }

    @Test fun guardsOn_blocksWhenSmbWasHeldBackForSafety() {
        assertTrue(
            BasalChannelSafetyGuards.shouldBlockBasalFirst(
                guardsEnabled = true, criticalSafetyZeroed = true, contextSuppressSmb = false, mealModeActive = false
            )
        )
    }

    @Test fun guardsOn_doesNotBlockWhenSmbWasSimplyNotRequested() {
        // Le mode Basal-First route vers le basal de façon délibérée : ce n'est pas un refus de sécurité
        // et il ne doit pas être bloqué ici.
        assertFalse(
            BasalChannelSafetyGuards.shouldBlockBasalFirst(
                guardsEnabled = true, criticalSafetyZeroed = false, contextSuppressSmb = false, mealModeActive = false
            )
        )
    }

    @Test fun mealMode_isNeverBlocked_evenUnderASafetyHold() {
        // Un mode repas déclaré doit appliquer sa basale sur toute sa durée : les garde-fous s'effacent.
        assertFalse(
            BasalChannelSafetyGuards.shouldBlockBasalFirst(
                guardsEnabled = true, criticalSafetyZeroed = true, contextSuppressSmb = true, mealModeActive = true
            )
        )
    }

    // ---------------------------------------------------------------- basalFirstAdaptiveMultiplier

    @Test fun guardsOff_forcesOne_asBefore() {
        assertEquals(1.0, BasalChannelSafetyGuards.basalFirstAdaptiveMultiplier(guardsEnabled = false, adaptiveMult = 0.70, mealModeActive = false))
    }

    @Test fun guardsOn_keepsProtectiveReduction() {
        assertEquals(0.70, BasalChannelSafetyGuards.basalFirstAdaptiveMultiplier(guardsEnabled = true, adaptiveMult = 0.70, mealModeActive = false))
    }

    @Test fun guardsOn_stillDiscardsAmplification() {
        // Une amplification du learner ne doit jamais franchir le plan basal-first.
        assertEquals(1.0, BasalChannelSafetyGuards.basalFirstAdaptiveMultiplier(guardsEnabled = true, adaptiveMult = 1.48, mealModeActive = false))
    }

    @Test fun guardsOn_neverRaisesTheRateVersusLegacy() {
        // Invariant central du lot 3 : à entrée identique, le multiplicateur retenu est toujours <= 1.0,
        // donc le taux ne peut que baisser par rapport au comportement actuel, jamais monter.
        var mult = 0.05
        while (mult <= 3.0) {
            val kept = BasalChannelSafetyGuards.basalFirstAdaptiveMultiplier(guardsEnabled = true, adaptiveMult = mult, mealModeActive = false)
            assertTrue(kept <= 1.0, "adaptiveMult=$mult a produit $kept > 1.0")
            mult += 0.05
        }
    }

    @Test fun degenerateMultipliers_failSafeToOne() {
        assertEquals(1.0, BasalChannelSafetyGuards.basalFirstAdaptiveMultiplier(guardsEnabled = true, adaptiveMult = 0.0, mealModeActive = false))
        assertEquals(1.0, BasalChannelSafetyGuards.basalFirstAdaptiveMultiplier(guardsEnabled = true, adaptiveMult = -1.0, mealModeActive = false))
        assertEquals(1.0, BasalChannelSafetyGuards.basalFirstAdaptiveMultiplier(guardsEnabled = true, adaptiveMult = Double.NaN, mealModeActive = false))
    }

    // ---------------------------------------------------------------- rejeu de ticks réels

    @Test fun prod20260802_0836_harmoniaBasalFirstAt5Uph_isDampedWhenLearnerDefends() {
        // Tick réel : BG 104, cible 115, IOB 0,12, PI-Fallback Mult=5,03x, HARMONIA_PRODUCTION_BASAL_FIRST,
        // TBR finale 5,00 U/h sur un profil 0,5-0,6 U/h. Le learner neural défendait à 0,70x, mais le plan
        // basal-first forçait adaptiveMult a 1,0 et jetait cette réduction.
        val legacy = BasalChannelSafetyGuards.basalFirstAdaptiveMultiplier(guardsEnabled = false, adaptiveMult = 0.70, mealModeActive = false)
        val guarded = BasalChannelSafetyGuards.basalFirstAdaptiveMultiplier(guardsEnabled = true, adaptiveMult = 0.70, mealModeActive = false)
        assertEquals(1.0, legacy)
        assertEquals(0.70, guarded)
        assertEquals(5.00, 5.00 * legacy, 1e-9)
        assertEquals(3.50, 5.00 * guarded, 1e-9)
    }

    @Test fun prod20260801_1732_smbHeldAtBg74_blocksTheBasalFirstChannel() {
        // Tick réel : BG 73,9 avec IOB +2,54 et TBR demandée 4,18 U/h. A cette glycémie le SMB est zéroé par
        // isCriticalSafetyCondition ; le canal basal-first ne doit alors plus s'ouvrir.
        assertTrue(
            BasalChannelSafetyGuards.shouldBlockBasalFirst(
                guardsEnabled = true, criticalSafetyZeroed = true, contextSuppressSmb = false, mealModeActive = false
            )
        )
        assertFalse(
            BasalChannelSafetyGuards.shouldBlockBasalFirst(
                guardsEnabled = false, criticalSafetyZeroed = true, contextSuppressSmb = false, mealModeActive = false
            )
        )
    }

    @Test fun mealMode_appliesItsBasalUndamped() {
        // Exclusion explicite : pendant un mode repas déclaré, la basale configurée doit s'appliquer telle
        // quelle, même si un learner voulait la réduire.
        assertEquals(
            1.0,
            BasalChannelSafetyGuards.basalFirstAdaptiveMultiplier(
                guardsEnabled = true, adaptiveMult = 0.70, mealModeActive = true
            )
        )
    }
}
