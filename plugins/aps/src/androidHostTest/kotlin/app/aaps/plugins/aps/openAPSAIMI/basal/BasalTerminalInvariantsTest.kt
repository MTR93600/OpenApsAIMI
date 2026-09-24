package app.aaps.plugins.aps.openAPSAIMI.basal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Caractérise les invariants terminaux du lot 2. Les cas nommés `prod…` rejouent des ticks réels du build
 * `4.0.0.0-dev.AIMI.310726` (paquet de support du 2026-08-02).
 */
class BasalTerminalInvariantsTest {

    private fun input(
        enabled: Boolean = true,
        rate: Double = 5.0,
        profileBasal: Double = 0.6,
        bg: Double = 104.0,
        target: Double = 115.0,
        eventual: Double? = 91.0,
        delta: Double = 2.5,
        iob: Double = 0.12,
        mealMode: Boolean = false,
        postHypo: Boolean = false,
    ) = BasalTerminalInvariants.Input(
        enabled = enabled,
        rateUph = rate,
        profileBasalUph = profileBasal,
        bgMgdl = bg,
        targetBgMgdl = target,
        eventualBgMgdl = eventual,
        deltaMgdl5m = delta,
        iobU = iob,
        mealModeActive = mealMode,
        postHypoActive = postHypo,
    )

    // ---------------------------------------------------------------- neutralité

    @Test fun disabled_isAStrictNoOp() {
        val r = BasalTerminalInvariants.resolve(input(enabled = false))
        assertEquals(5.0, r.rateUph)
        assertNull(r.boundBy)
    }

    @Test fun mealMode_isExempt() {
        val r = BasalTerminalInvariants.resolve(input(mealMode = true))
        assertEquals(5.0, r.rateUph)
        assertNull(r.boundBy)
        assertEquals("meal_mode_exempt", r.trace)
    }

    @Test fun aboveTargetAndRising_isNotBound() {
        val r = BasalTerminalInvariants.resolve(input(bg = 180.0, target = 115.0, eventual = 210.0))
        assertEquals(5.0, r.rateUph)
        assertNull(r.boundBy)
    }

    @Test fun zeroRate_staysZero() {
        val r = BasalTerminalInvariants.resolve(input(rate = 0.0))
        assertEquals(0.0, r.rateUph)
        assertNull(r.boundBy)
    }

    @Test fun missingEventual_doesNotTriggerBelowTarget() {
        val r = BasalTerminalInvariants.resolve(input(eventual = null, iob = 1.0, delta = 2.0))
        assertNull(r.boundBy)
    }

    // ---------------------------------------------------------------- invariants

    @Test fun belowTargetWithBelowTargetPrediction_capsAtProfileBasal() {
        val r = BasalTerminalInvariants.resolve(input(bg = 104.0, target = 115.0, eventual = 91.0))
        assertEquals(0.6, r.rateUph)
        assertEquals("below_target", r.boundBy)
    }

    @Test fun postHypoRecovery_capsAtProfileBasal() {
        // Au-dessus de la cible et en montée : seul le verrou post-hypo peut lier ici.
        val r = BasalTerminalInvariants.resolve(
            input(bg = 130.0, target = 115.0, eventual = 160.0, postHypo = true)
        )
        assertEquals(0.6, r.rateUph)
        assertEquals("post_hypo", r.boundBy)
    }

    @Test fun negativeIobWithoutRise_capsAtProfileBasal() {
        val r = BasalTerminalInvariants.resolve(
            input(bg = 130.0, target = 115.0, eventual = 160.0, iob = -1.97, delta = -0.5)
        )
        assertEquals(0.6, r.rateUph)
        assertEquals("negative_iob", r.boundBy)
    }

    @Test fun negativeIobWithConfirmedRise_isNotBound() {
        val r = BasalTerminalInvariants.resolve(
            input(bg = 130.0, target = 115.0, eventual = 160.0, iob = -1.97, delta = 3.0)
        )
        assertNull(r.boundBy)
        assertEquals(5.0, r.rateUph)
    }

    // ---------------------------------------------------------------- contrat global

    @Test fun neverRaisesTheRate() {
        // Invariant central du lot 2 : réduction seule, sur toute la plage.
        var rate = 0.05
        while (rate <= 12.0) {
            for (postHypo in listOf(false, true)) {
                for (iob in listOf(-2.0, 0.0, 3.0)) {
                    val r = BasalTerminalInvariants.resolve(input(rate = rate, iob = iob, postHypo = postHypo))
                    assertTrue(r.rateUph <= rate, "rate=$rate a produit ${r.rateUph}")
                }
            }
            rate += 0.25
        }
    }

    @Test fun alreadyBelowCap_isLeftUntouchedAndNotReported() {
        val r = BasalTerminalInvariants.resolve(input(rate = 0.3, postHypo = true))
        assertEquals(0.3, r.rateUph)
        assertNull(r.boundBy) // aucun changement → rien à signaler
    }

    @Test fun strictestCapWins() {
        // post_hypo et negative_iob plafonnent tous deux au basal profil ; le minimum est retenu et tracé.
        val r = BasalTerminalInvariants.resolve(
            input(bg = 104.0, target = 115.0, eventual = 91.0, iob = -1.0, delta = -1.0, postHypo = true)
        )
        assertEquals(0.6, r.rateUph)
        assertTrue(r.trace.contains("below_target"))
        assertTrue(r.trace.contains("post_hypo"))
        assertTrue(r.trace.contains("negative_iob"))
    }

    // ---------------------------------------------------------------- rejeu de ticks réels

    @Test fun prod20260802_0836_fiveUphAtBg104WithPredictionBelowTarget() {
        // BG 104, cible 115, eventual dosant 91, TBR finale 5,00 U/h sur profil 0,6 : la règle oref
        // « eventual < cible ⇒ pas d'insuline » n'existait pas côté basal.
        val r = BasalTerminalInvariants.resolve(
            input(rate = 5.00, profileBasal = 0.6, bg = 104.0, target = 115.0, eventual = 91.0, iob = 0.12)
        )
        assertEquals("below_target", r.boundBy)
        assertEquals(0.6, r.rateUph)
    }

    @Test fun prod20260802_0611_fourUphAtBg94WithPredictionBelowTarget() {
        val r = BasalTerminalInvariants.resolve(
            input(rate = 4.05, profileBasal = 0.6, bg = 94.2, target = 115.0, eventual = 86.0, iob = 0.06)
        )
        assertEquals("below_target", r.boundBy)
        assertEquals(0.6, r.rateUph)
    }

    @Test fun prod20260801_1732_fourUphAtBg74IsBoundByPostHypo() {
        // BG 73,9 avec IOB +2,54 et eventual 389 : ni below_target ni negative_iob ne lient, c'est le
        // verrou post-hypo qui doit borner.
        val r = BasalTerminalInvariants.resolve(
            input(rate = 4.18, profileBasal = 0.6, bg = 73.9, target = 100.0, eventual = 389.0, iob = 2.54, delta = 2.0, postHypo = true)
        )
        assertEquals("post_hypo", r.boundBy)
        assertEquals(0.6, r.rateUph)
    }
}
